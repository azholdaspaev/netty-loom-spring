# netty-loom-spring

A Java 24 library connecting Netty with Spring Web via virtual threads.

## Project Overview

This library provides seamless integration between Netty's high-performance networking and Spring's web framework, leveraging Java 24's virtual threads (Project Loom) for efficient handling of blocking operations.

## Build Commands

```bash
# Build the project
./gradlew build

# Run tests
./gradlew test

# Run all checks (compile + test + spotless)
./gradlew check

# Clean build
./gradlew clean build

# Format code
./gradlew spotlessApply
```

## AIDD Framework

This project uses an AI-Driven Development (AIDD) framework. See `.claude/agents/` and `.claude/commands/` for details.

### Quick Reference

| Command | Purpose |
|---------|---------|
| `/spec` | Technical specification (API contract, module placement) |
| `/research` | Codebase and external source analysis |
| `/plan` | Architecture design (class diagrams, thread models) |
| `/tasks` | Task breakdown with module prefixes |
| `/implement` | TDD code implementation |
| `/review` | Code review (thread safety, ByteBuf, boundaries) |
| `/validate` | Quality gate audit |
| `/quick-feature` | Quick flow for small features |
| `/quick-fix` | Quick flow for bug fixes |

### Development Flows

**Full Flow** (Complex Features):
```
/spec → /research → /plan → /tasks → /implement → /review → /validate
```

**Quick Flow** (Small Changes):
```
/quick-feature → code + tests → review → done
/quick-fix → failing test → fix → verify → done
```

## Project Conventions

### Java 24
- Use virtual threads for blocking operations
- Use records for DTOs
- Use sealed interfaces for type hierarchies
- Apply @Nullable/@NonNull annotations

### Netty
- Never block EventLoop threads
- Always release ByteBuf after use
- Use virtual thread executor for blocking work

### Testing
- All tests must use Given/When/Then structure with `// Given`, `// When`, `// Then` comments
- For combined assertion-and-action cases use `// When / Then`

### Spring
- Use @ConditionalOnClass, @ConditionalOnMissingBean
- Configuration properties prefix: `netty.loom.*`
- Support standard Spring MVC annotations

See `.claude/rules/` for detailed conventions.

## Directory Structure

```
.claude/                    # AIDD framework
├── agents/            # Agent definitions (6 agents)
│   ├── spec-writer.md     # API specification
│   ├── researcher.md      # Codebase analysis
│   ├── architect.md       # Architecture design
│   ├── implementer.md     # TDD implementation
│   ├── reviewer.md        # Code review
│   └── validator.md       # Gate auditing
├── commands/          # Workflow commands (7 commands)
│   ├── spec.md
│   ├── research.md
│   ├── plan.md
│   ├── tasks.md
│   ├── implement.md
│   ├── review.md
│   └── validate.md
└── rules/             # Project conventions
    ├── java-conventions.md
    ├── netty-conventions.md
    ├── module-boundaries.md
    └── testing-conventions.md

docs/                   # Feature documentation
└── {feature-name}/    # Per-feature docs
    ├── spec.md
    ├── research.md
    ├── plan.md
    ├── tasks.md
    └── review.md
```
