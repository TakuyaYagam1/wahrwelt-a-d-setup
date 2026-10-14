const eslint = require('@eslint/js')
const eslintConfigPrettier = require('eslint-config-prettier/flat')
const globals = require('globals')
const vue = require('eslint-plugin-vue')

module.exports = [
  {
    ignores: ['dist/**', 'node_modules/**']
  },
  {
    files: ['**/*.{js,mjs,cjs,vue}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: globals.browser
    },
    rules: eslint.configs.recommended.rules
  },
  {
    files: ['**/*.cjs'],
    languageOptions: {
      sourceType: 'commonjs',
      globals: globals.node
    }
  },
  ...vue.configs['flat/essential'],
  eslintConfigPrettier
]
