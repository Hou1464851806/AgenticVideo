import prettier from "eslint-config-prettier";
import js from "@eslint/js";
import ts from "typescript-eslint";
import vue from "eslint-plugin-vue";
export default [
  {
    ignores: [
      "dist/**",
      "node_modules/**",
      "playwright-report/**",
      "test-results/**",
    ],
  },
  js.configs.recommended,
  ...ts.configs.recommended,
  ...vue.configs["flat/recommended"],
  {
    files: ["**/*.vue"],
    languageOptions: { parserOptions: { parser: ts.parser } },
  },
  {
    languageOptions: {
      globals: {
        console: "readonly",
        process: "readonly",
        fetch: "readonly",
        window: "readonly",
        document: "readonly",
        URL: "readonly",
        setTimeout: "readonly",
        clearTimeout: "readonly",
        crypto: "readonly",
        File: "readonly",
        Event: "readonly",
        HTMLInputElement: "readonly",
        AbortController: "readonly",
      },
    },
  },
  prettier,
];
