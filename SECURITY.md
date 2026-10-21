# Security Policy

## Supported versions

JDesk Framework has not reached 1.0. Security fixes are made on the latest `main` only.

## Reporting a vulnerability

Please do not open a public issue. Instead, use GitHub's private vulnerability reporting: open the **Security** tab of this repository and choose **Report a vulnerability**. Include the affected component, the steps to reproduce, and the impact you expect.

This is a small project run by a single maintainer. We aim to acknowledge reports within 7 days and will keep you informed until the issue is resolved.

## Known limitations

- **Embedded MCP server** (`com.jdesk.frame.mcp.McpServerService`). The server runs only after an application starts it. It listens on `127.0.0.1` only, and it rejects requests whose `Host` or `Origin` is anything other than `127.0.0.1:<port>` or `localhost:<port>`. It has **no application-level authentication**, so any process on the same machine that learns the port can call the registered tools. Register only tools you are willing to expose to local processes, or do not start the server.
