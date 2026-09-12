# Account Deletion Operations

MyStuff normally starts Account Deletion from the authenticated Android app. A verified external
email request uses the same durable backend job through the private `manuallyDeleteAccount`
Firebase Function.

## Before processing an email request

1. Confirm the request was sent from the Google email address used for MyStuff.
2. Reply to that address and receive explicit confirmation that deletion is irreversible.
3. Select the intended production Firebase project. The examples below use `mystuff-ai-app`.

The function is private through Google Cloud IAM. Invoke it only with an operator identity that
has permission to invoke the deployed function.

## Preview

Previewing does not enqueue deletion:

```bash
gcloud functions call manuallyDeleteAccount \
  --gen2 \
  --region=australia-southeast1 \
  --project=mystuff-ai-app \
  --data='{"email":"person@example.com","execute":false}'
```

Check the returned Member ID, whether the person owns a Household, the Household name, and its
Member and Item counts. An Owner request deletes the entire Household for every Member.

## Execute

Repeat the call with `execute` set to `true`:

```bash
gcloud functions call manuallyDeleteAccount \
  --gen2 \
  --region=australia-southeast1 \
  --project=mystuff-ai-app \
  --data='{"email":"person@example.com","execute":true}'
```

The response confirms that the job was accepted. Firestore and Storage rules deny further app
access as soon as the job exists. Cleanup then retries asynchronously and deletes Firebase
Authentication last.

## Resume a failed job

Use the Member ID returned by the preview or stored in `accountDeletionJobs/{memberId}`:

```bash
gcloud functions call manuallyDeleteAccount \
  --gen2 \
  --region=australia-southeast1 \
  --project=mystuff-ai-app \
  --data='{"memberId":"member-id","resume":true}'
```

The cleanup is idempotent. Never delete a pending job merely to restore access; inspect Function
and Cloud Logging errors, correct the underlying service failure, and resume it.
