# Code Review Style Guide

## Core principles
- Prioritize correctness, security, and regression risk.
- Avoid low-value style nitpicks unless they hide a real risk.
- Prefer concise, actionable comments with concrete fixes.

## Severity rubric
- HIGH: bugs, security issues, data loss, broken behavior
- MEDIUM: maintainability issues likely to cause bugs soon
- LOW: optional improvements with clear value

## What to focus on
- Null safety and error handling
- Boundary conditions and off-by-one mistakes
- Query and API misuse that can fail in production
- Missing tests for changed logic

## What to avoid
- Pure style-only comments with no impact
- Repeating comments on the same root cause
- Suggesting large refactors outside PR scope
