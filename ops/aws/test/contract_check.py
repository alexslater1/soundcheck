#!/usr/bin/env python3
"""Contract check: the ECS task definitions against what the backend reads.

Usage: ops/aws/test/contract_check.py <backend resources dir> <cdk.out dir> [<cdk.out dir> ...]

For every task definition in every SoundcheckCompute template given, it fails when
  - a variable the task gets (plain or from a secret) is read by nothing: not a ${PLACEHOLDER} in
    application.yaml / application-aws.yaml, and not one of the Spring Boot properties set by relaxed
    binding listed below;
  - the `aws` profile leaves a placeholder without a default (so the process can't start) and the task
    doesn't provide it;
  - a role-specific requirement below is missing (the JWT key on the api role);
  - a feature flag is on without its key (PUSH_ENABLED without PUSH_CREDENTIALS_JSON, OTLP_ENABLED
    without OTLP_AUTHORIZATION and OTEL_EXPORTER_OTLP_ENDPOINT).
Standard library only, so it runs on CI's infra runner as it is. `make aws-test` and CI run it through contract-check.sh.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

PLACEHOLDER = re.compile(r"\$\{([A-Z][A-Z0-9_]*)(:[^}]*)?\}")

# Read by Spring Boot through relaxed binding (SPRING_FLYWAY_ENABLED → spring.flyway.enabled), not
# through a placeholder. Each is a real Boot or Modulith property; the reason is why a task sets it.
RELAXED_BINDING = {
    "SPRING_PROFILES_ACTIVE": "spring.profiles.active: the aws profile",
    "SPRING_FLYWAY_ENABLED": "spring.flyway.enabled: only the API migrates",
    "SPRING_MODULITH_EVENTS_REPUBLISH_OUTSTANDING_EVENTS_ON_RESTART": (
        "spring.modulith.events.republish-outstanding-events-on-restart: only the API republishes"
    ),
}

# What a role needs whatever the yaml says (checked in code, not by a missing placeholder).
ROLE_REQUIRES = {
    "api": {"JWT_PRIVATE_KEY_PEM"},  # JwtConfig: a key is required on aws when the roles include api
}

FLAG_REQUIRES = {
    "PUSH_ENABLED": {"PUSH_CREDENTIALS_JSON"},
    "OTLP_ENABLED": {"OTLP_AUTHORIZATION", "OTEL_EXPORTER_OTLP_ENDPOINT"},
}


def placeholders(path: Path) -> dict[str, bool]:
    """Placeholder name → whether it has a default, for one yaml file."""
    found: dict[str, bool] = {}
    for name, default in PLACEHOLDER.findall(path.read_text()):
        found[name] = found.get(name, False) or bool(default)
    return found


def tasks(cdk_out: Path) -> list[tuple[str, dict[str, str], set[str]]]:
    """(logical id, plain environment, secret names) per container in the Compute template."""
    template = json.loads((cdk_out / "SoundcheckCompute.template.json").read_text())
    out = []
    for logical_id, resource in template["Resources"].items():
        if resource["Type"] != "AWS::ECS::TaskDefinition":
            continue
        for container in resource["Properties"]["ContainerDefinitions"]:
            env = {e["Name"]: e.get("Value") for e in container.get("Environment", [])}
            secrets = {s["Name"] for s in container.get("Secrets", [])}
            out.append((f"{logical_id}/{container['Name']}", env, secrets))
    return out


def check(resources: Path, cdk_outs: list[Path]) -> list[str]:
    base = placeholders(resources / "application.yaml")
    aws = placeholders(resources / "application-aws.yaml")
    read = set(base) | set(aws) | set(RELAXED_BINDING)
    # The aws profile's value wins where it sets one; a placeholder there without a default is required.
    required = {name for name, has_default in aws.items() if not has_default}
    required |= {name for name, has_default in base.items() if not has_default and name not in aws}

    problems: list[str] = []
    seen = 0
    for cdk_out in cdk_outs:
        for where, env, secrets in tasks(cdk_out):
            seen += 1
            given = set(env) | secrets
            label = f"{cdk_out.name}:{where}"
            if env.get("SPRING_PROFILES_ACTIVE") != "aws":
                problems.append(f"{label}: SPRING_PROFILES_ACTIVE is not 'aws'")
            roles = set((env.get("SOUNDCHECK_ROLES") or "").split(","))
            for name in sorted(given - read):
                problems.append(f"{label}: {name} is set but nothing in the backend reads it")
            for name in sorted(required - given):
                problems.append(f"{label}: {name} has no default on the aws profile and the task doesn't set it")
            for role in sorted(roles & set(ROLE_REQUIRES)):
                for name in sorted(ROLE_REQUIRES[role] - given):
                    problems.append(f"{label}: role {role} needs {name}")
            for flag, needs in FLAG_REQUIRES.items():
                if env.get(flag) == "true":
                    for name in sorted(needs - given):
                        problems.append(f"{label}: {flag}=true without {name}")
            dupes = set(env) & secrets
            for name in sorted(dupes):
                problems.append(f"{label}: {name} is both a plain variable and a secret")
    if seen == 0:
        problems.append("no task definitions found: is the Compute template in the cdk.out given?")
    return problems


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(__doc__.splitlines()[2], file=sys.stderr)
        return 2
    resources, cdk_outs = Path(argv[1]), [Path(p) for p in argv[2:]]
    problems = check(resources, cdk_outs)
    for p in problems:
        print(f"FAIL {p}")
    if problems:
        return 1
    count = sum(len(tasks(c)) for c in cdk_outs)
    print(f"contract check: {count} task definitions across {len(cdk_outs)} synths match the backend's properties")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
