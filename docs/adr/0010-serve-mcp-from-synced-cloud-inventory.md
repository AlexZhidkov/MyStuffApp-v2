# Serve MCP from the synced cloud Inventory

External MCP clients read a Member's synced Household Inventory through a remote
service rather than an MCP server running in the Android app. This makes access
independent of the phone's connectivity and the app lifecycle, accepting service
hosting and authorization work; changes still waiting to sync from a phone are
not available remotely. The first version is read-only, exposes Item Path, name,
and Description, and includes listing Child Item names. Gemini's Custom app
connection flow is the primary Member setup, with Gemini CLI used for the first
connectivity check. Use serverless hosting in the existing Firebase/Google
Cloud project with cold starts allowed, initially validating the existing HTTP
function deployment path. This trades VM maintenance and idle capacity for
request-based service costs and occasional startup latency.
