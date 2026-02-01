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

# Run all checks
./gradlew check

# Clean build
./gradlew clean build
```

## AIDD Framework

This project uses an AI-Driven Development (AIDD) framework. See `.claude/CLAUDE.md` for the full workflow documentation.

### Quick Reference

| Command | Purpose |
|---------|---------|
| `/idea` | Start a new feature with PRD |
| `/research` | Technical research |
| `/design` | Architecture design |
| `/plan` | Implementation planning |
| `/tasks` | Task breakdown |
| `/implement` | Code implementation |
| `/review` | Code review |
| `/test` | QA testing |
| `/docs` | Documentation |
| `/validate` | Gate validation |
| `/release` | Release execution |
| `/quick-feature` | Quick flow for small features |
| `/quick-fix` | Quick flow for bug fixes |

### Development Flows

**Full Flow** (Complex Features):
```
/idea → /research → /design → /plan → /tasks → /implement → /review → /test → /docs → /validate → /release
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

### Spring
- Use @ConditionalOnClass, @ConditionalOnMissingBean
- Configuration properties prefix: `netty.loom.*`
- Support standard Spring MVC annotations

See `.claude/rules/` for detailed conventions.

## Directory Structure

```
.claude/                    # AIDD framework
├── CLAUDE.md          # Main AI instructions
├── agents/            # Agent definitions
├── commands/          # Workflow commands
├── templates/         # Document templates
├── rules/             # Project conventions
└── quality-gates/     # Gate definitions

docs/                   # Feature documentation
├── {feature-name}/    # Per-feature docs
└── releases/          # Release notes

src/                    # Source code
├── main/java/         # Main sources
└── test/java/         # Test sources
```
