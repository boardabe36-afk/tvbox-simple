# Security Policy

## Scope

This project is a simple Android TV client. It does not include video content, built-in source lists, accounts, API keys, or signing keys.

## Reporting a Vulnerability

Please open a GitHub issue with a clear reproduction path. Do not publish private keys, source URLs, or user data in the issue.

## Secrets

Release signing files are intentionally ignored by git:

- `keystore.properties`
- `*.keystore`, `*.jks`, `*.p12`, `*.pem`, `*.key`

Use environment variables or a local `keystore.properties` file for release signing.
