# role-relay

[繁體中文](README.zh-TW.md)

**role-relay** lets you describe a team of AI agents in plain prompt files (who plans, who builds, who reviews) and runs them in sequence, each handing its output to the next. It is designed to run on free model tiers through [FreeLLMAPI](https://github.com/tashfeenahmed/freellmapi), an OpenAI-compatible router. Bring your own API keys and follow each provider's terms.

> Status: early (0.1.0-SNAPSHOT). Text-only relay; no tool use, no file editing. Tested with unit tests, a mock server, and a few manual runs against a local FreeLLMAPI instance (Docker, Windows).

## How it works

```
task ──► planner ──► builder ──► reviewer ──► final output
           │            │            │
           └────────────┴────────────┴──► runs/<timestamp>/  (what each role was sent, what it replied, which model answered)
```

- A **role** is one Markdown file in `.agents/roles/`. The text after the front matter is sent as the system prompt.
- **`relay.yaml`** lists the roles in order.
- Each role receives the task plus the previous role's output (`handoff: previous`) or every earlier output (`handoff: all`).
- A step that hits a **temporary** failure (HTTP 429, 500/502/503/504, timeout, or an empty or garbled reply) is retried up to `retries` times (default 1). Other failures, such as a wrong API key, are not retried. If a step still fails, the relay **stops there** and never skips ahead. Every attempt is recorded in the run log.
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

The output of the role chosen by `output_from` (or, if unset, the last role) is printed to standard output. The run log location and the model used for each step are printed to standard error.

Other commands:

```bash
java -jar target/role-relay.jar plan --task "..."           # let dispatcher_model choose roles (see below)
java -jar target/role-relay.jar roles                       # list role files
java -jar target/role-relay.jar run --task-file task.md     # read the task from a file
java -jar target/role-relay.jar run --config other.yaml --task "..."
```

## Let a dispatcher choose the roles (plan, then run)

Instead of a fixed `steps` list, you can ask one model to pick the roles for a task. This is split into two commands so that a person sees the plan before anything runs:

```bash
# 1. Set a specific dispatcher model in relay.yaml (not "auto"), e.g.
#    dispatcher_model: gemini-3.5-flash
java -jar target/role-relay.jar plan --task "Explain virtual threads in three points, in Chinese"
#    -> writes plans/<time>.yaml and plans/<time>.log.md

# 2. Read and edit plans/<time>.yaml if needed, then run it. The plan file carries its own task.
java -jar target/role-relay.jar run --config plans/<time>.yaml
```

What the dispatcher sees: the task, and each role's **name and one-line description** only (not the role prompts), plus `max_steps`. It chooses roles; it does not choose models.

What is checked before a plan is written: the reply must be a single JSON object (optionally in a ```` ```json ```` fence), every role must exist, and the number of steps must not exceed `max_steps`. If any check fails, nothing is written except `plans/<time>-REJECTED.log.md` with the raw reply.

To change the dispatcher's instructions, create `.agents/dispatcher.md`; otherwise a built-in prompt is used.

### Choosing a dispatcher model

The quality of the plan depends heavily on the dispatcher model. With `auto`, the router may pick a different, possibly weak, model every time, so the plan command requires `dispatcher_model` to be set explicitly. Which free model plans best is not known in advance; try a few candidates on the same tasks and compare the plans (planning calls are small and nothing is executed).

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
dispatcher_model: gemini-3.5-flash   # used only by the plan command; set a specific model
max_steps: 6                         # upper limit for plans
retries: 1                           # extra attempts after a temporary failure (0-3)
output_from: [translator, builder]   # whose output to print on screen; first one present wins
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
- The dispatcher can only choose among existing role files; it cannot create roles.
- With `model: auto`, results are not reproducible: the router may choose different models on each run.

## License

[Apache License 2.0](LICENSE). FreeLLMAPI is a separate project under the MIT License; role-relay calls it over HTTP and does not include its code.
