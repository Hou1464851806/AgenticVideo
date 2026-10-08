package com.agenticvideo.auth;

import com.agenticvideo.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  public record Credentials(String email, String password) {}

  public record Identity(UUID id, String email) {}

  private record Account(UUID id, String email, String password) {}

  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwords;
  private final HttpSessionSecurityContextRepository contexts;
  private final HttpSessionCsrfTokenRepository csrf;
  private final String dummyPassword;

  public AuthController(
      JdbcTemplate jdbc,
      PasswordEncoder passwords,
      HttpSessionSecurityContextRepository contexts,
      HttpSessionCsrfTokenRepository csrf) {
    this.jdbc = jdbc;
    this.passwords = passwords;
    this.contexts = contexts;
    this.csrf = csrf;
    dummyPassword = passwords.encode(UUID.randomUUID().toString());
  }

  @GetMapping("/csrf")
  Map<String, String> csrf(CsrfToken token) {
    return Map.of("header_name", token.getHeaderName(), "token", token.getToken());
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  Identity register(@RequestBody Credentials input) {
    String email = validate(input);
    UUID id = UUID.randomUUID();
    try {
      jdbc.update(
          "insert into users(id,email,password_hash) values (?,?,?)",
          id,
          email,
          passwords.encode(input.password()));
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw new ApiException(409, "EMAIL_ALREADY_REGISTERED", "邮箱已注册");
    }
    return new Identity(id, email);
  }

  @PostMapping("/login")
  Identity login(
      @RequestBody Credentials input, HttpServletRequest request, HttpServletResponse response) {
    String email = validate(input);
    var accounts =
        jdbc.query(
            "select id,email,password_hash from users where email=?",
            (rs, row) -> new Account(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)),
            email);
    Account account = accounts.isEmpty() ? null : accounts.getFirst();
    boolean matches =
        passwords.matches(input.password(), account == null ? dummyPassword : account.password());
    if (account == null || !matches) throw new ApiException(401, "LOGIN_INVALID", "邮箱或密码错误");
    request.getSession();
    request.changeSessionId();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            account.id().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    SecurityContextHolder.setContext(context);
    contexts.saveContext(context, request, response);
    csrf.saveToken(null, request, response);
    return new Identity(account.id(), account.email());
  }

  @GetMapping("/me")
  Identity me(java.security.Principal principal) {
    if (principal == null) throw new ApiException(401, "AUTH_REQUIRED", "请先登录");
    var users =
        jdbc.query(
            "select id,email from users where id=?",
            (rs, row) -> new Identity(rs.getObject(1, UUID.class), rs.getString(2)),
            UUID.fromString(principal.getName()));
    if (users.isEmpty()) throw new ApiException(401, "AUTH_REQUIRED", "请重新登录");
    return users.getFirst();
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void logout(HttpServletRequest request) {
    var session = request.getSession(false);
    if (session != null) session.invalidate();
    SecurityContextHolder.clearContext();
  }

  private static String validate(Credentials input) {
    if (input.email() == null
        || input.password() == null
        || input.email().length() > 254
        || !input.email().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
        || input.password().length() < 12
        || input.password().length() > 128)
      throw new ApiException(422, "CREDENTIALS_INVALID", "请输入有效邮箱和 12–128 位密码");
    return input.email().trim().toLowerCase(Locale.ROOT);
  }
}
