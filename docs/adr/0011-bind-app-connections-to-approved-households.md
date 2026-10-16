# Bind App Connections to approved Households

Members connect external applications through browser Google sign-in and
read-only authorization for one named Household, requiring an existing MyStuff
Account and active Household Access. Give each App Connection its own revocation
lifecycle and check current Household Access on every request, rather than
automatically following a Member into a different Household or revoking all of
their MyStuff sessions to disconnect one application. This adds connection
management and reapproval when changing Households, preserving explicit control
over which Household an external application can read.

Active connections renew automatically; 90 days without use requires fresh
approval. Losing Household Access invalidates the connection, and restoring
access does not reactivate it without fresh approval.
