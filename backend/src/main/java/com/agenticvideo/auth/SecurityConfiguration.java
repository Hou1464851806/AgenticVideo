package com.agenticvideo.auth;

import com.agenticvideo.api.Errors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
public class SecurityConfiguration {
  @Bean
  PasswordEncoder passwordEncoder() {
    return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  }

  @Bean
  HttpSessionCsrfTokenRepository csrfRepository() {
    return new HttpSessionCsrfTokenRepository();
  }

  @Bean
  HttpSessionSecurityContextRepository contextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  UserDetailsService userDetailsService(JdbcTemplate jdbc) {
    return email -> {
      var users =
          jdbc.query(
              "select email,password_hash from users where email=?",
              (rs, row) ->
                  User.withUsername(rs.getString(1))
                      .password(rs.getString(2))
                      .roles("USER")
                      .build(),
              email);
      if (users.isEmpty()) throw new UsernameNotFoundException("User not found");
      return users.getFirst();
    };
  }

  @Bean
  @ConditionalOnWebApplication
  SecurityFilterChain security(
      HttpSecurity http,
      HttpSessionCsrfTokenRepository csrf,
      HttpSessionSecurityContextRepository contexts)
      throws Exception {
    return http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/api/v1/auth/csrf",
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/health")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .csrf(c -> c.csrfTokenRepository(csrf))
        .securityContext(c -> c.securityContextRepository(contexts))
        .formLogin(c -> c.disable())
        .httpBasic(c -> c.disable())
        .logout(c -> c.disable())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (request, response, exception) ->
                            Errors.write(request, response, 401, "AUTH_REQUIRED", "请先登录"))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            Errors.write(request, response, 403, "CSRF_INVALID", "请求校验失败，请刷新后重试")))
        .build();
  }
}
