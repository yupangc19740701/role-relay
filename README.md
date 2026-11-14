# role-relay

[繁體中文](README.zh-TW.md)

**role-relay** lets you describe a team of AI agents in plain prompt files (who plans, who builds, who reviews) and runs them in sequence, each handing its output to the next. It is designed to run on free model tiers through [FreeLLMAPI](https://github.com/tashfeenahmed/freellmapi), an OpenAI-compatible router. Bring your own API keys and follow each provider's terms.

> Status: early (0.1.0-SNAPSHOT). Text-only relay; no tool use, no file editing.

## How it works

```
task ──► planner ──► builder ──► reviewer ──► final output
           │            │            │
           └────────────┴────────────┴──► runs/<timestamp>/  (what each role was sent, what it replied, which model answered)
```

- A **role** is one Markdown file in `.agents/roles/`. The text after the front matter is sent as the system prompt.
- **`relay.yaml`** lists the roles in order.
- Each role receives the task plus the previous role's output (`handoff: previous`) or every earlier output (`handoff: all`).
- The relay **stops at the first failure**. It does not retry or skip ahead on its own.
- Every run writes a log folder, including **which model actually answered**: FreeLLMAPI reports this in the `X-Routed-Via` header. With `model: auto` the router may pick a different model on every step, so the log is the only reliable record.

## Requirements

- Java 21
- Maven 3.9+ (to build)
- A running [FreeLLMAPI](https://github.com/tashfeenahmed/freellmapi) instance with at least one provider key added, or any other OpenAI-compatible endpoint

## Quick start

```bash
mvn -B verify                       # builds target/role-relay.jar and runs the tests

# Check the configuration without calling any model
java -jar target/role-relay.jar run --task "Write a README outline for a CLI tool" --dry-run

# Real run (FreeLLMAPI: copy the unified key from its dashboard's Keys page)
export ROLE_RELAY_API_KEY=freellmapi-...          # Windows PowerShell: $env:ROLE_RELAY_API_KEY="freellmapi-..."
java -jar target/role-relay.jar run --task "Write a README outline for a CLI tool"
```

The final role's reply is printed to standard output. The run log location and the model used for each step are printed to standard error.

Other commands:

```bash
java -jar target/role-relay.jar roles                       # list role files
java -jar target/role-relay.jar run --task-file task.md     # read the task from a file
java -jar target/role-relay.jar run --config other.yaml --task "..."
```

## Writing a role

`.agents/roles/translator.md`:

```markdown
---
name: translator
description: Translates the previous output into Traditional Chinese.
model: auto
---
You are the translator. Translate the material you receive into Traditional Chinese.
Keep code blocks unchanged. Do not add content.
```

| Field | Required | Meaning |
|---|---|---|
| `name` | yes | Must match the file name. Lowercase letters, digits, `-` and `_`. |
| `description` | no | Shown by `roles` and in the run log. |
| `model` | no | Model to request. Falls back to `default_model` in `relay.yaml`. |

Unknown fields are rejected, so a typo fails loudly instead of being ignored.

## relay.yaml

```yaml
endpoint: http://localhost:3001/v1   # FreeLLMAPI default
default_model: auto                  # FreeLLMAPI: "auto" lets the router choose
allow_remote: false                  # remote endpoints need https and this set to true
timeout_seconds: 180
roles_dir: .agents/roles
runs_dir: runs
handoff: previous                    # previous | all
steps:
  - role: planner
  - role: builder
    model: gemini-2.5-flash          # optional per-step override
  - role: reviewer
```

Model precedence: step `model` → role file `model` → `default_model`.

## Safety defaults

- Plain `http` is accepted only for `localhost`. A remote endpoint must use `https` **and** set `allow_remote: true`.
- Redirects are not followed; responses over 4 MiB are rejected; there is no silent fallback to another endpoint.
- The API key is read only from the `ROLE_RELAY_API_KEY` environment variable and is never written to the run log.
- Earlier roles' output is passed to the next role as material, with a note that it does not override the role's instructions. This reduces, but does not prevent, prompt injection between roles.
- role-relay only reads role files. It never generates or installs roles by itself.

## About free tiers

FreeLLMAPI describes itself as intended for personal experimentation. Free tiers change often, have rate limits, and each provider has its own terms. role-relay does not encourage creating multiple accounts to stack quotas. If a provider's terms forbid a use, do not route it through role-relay.

## Limitations

- Text in, text out. Roles cannot run commands, read files or call tools.
- Sequential only; no branching or parallel steps.
- With `model: auto`, results are not reproducible: the router may choose different models on each run.

## License

[Apache License 2.0](LICENSE). FreeLLMAPI is a separate project under the MIT License; role-relay calls it over HTTP and does not include its code.
