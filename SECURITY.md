# Security Policy

> Status: current policy  
> Last verified: 2026-07-24  
> Sources of truth: `docs/security.md`, runtime/tool tests, release workflows

## Supported versions

Until the first stable release, security fixes target the latest `0.x` minor line. Older snapshots
and unreleased commits are not supported releases.

## Reporting a vulnerability

Do not open a public issue containing an exploit, credential, private prompt, user data or database
content. Use GitHub's private vulnerability reporting for
`zyblw/zyblw-agent`. Include the affected artifact/version, impact, minimal reproduction and any
known mitigation.

Do not send real API keys or production data. Maintainers will acknowledge a valid report, assess
severity, prepare a private fix and publish an advisory when a safe release is available.

## Security boundary

zyblw-agent is a harness, not a security sandbox by itself. Host applications remain responsible
for authentication, tenant authorization, credential storage, database/network isolation, tool
allowlists, human approval, data retention and incident response.

The model is never an authorization authority. External content is untrusted data, and side effects
must pass application-side validation and policy before execution.
