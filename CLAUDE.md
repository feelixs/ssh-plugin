# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands
- Build: `./gradlew build`
- Clean and build: `./gradlew clean build`
- Run IDE with plugin: `./gradlew runIde`
- Run tests: `./gradlew test`
- Run a single test: `./gradlew test --tests "com.github.feelixs.sshplugin.MyPluginTest.testXMLFile"`
- Check code coverage: `./gradlew koverXmlReport`
- Verify code coverage: `./gradlew koverVerify`

## Code Style Guidelines
- Kotlin convention: Use 4 spaces for indentation
- Imports: Group by package, no wildcards imports
- Naming: CamelCase for classes, lowerCamelCase for variables/methods
- Error handling: Use nullable types and handle nulls properly  
- Use `val` over `var` when possible
- Add descriptive comments for classes and complex methods
- Use data classes for model objects
- Mark UI components with appropriate annotations
- Follow IntelliJ platform plugin development guidelines
- Use constructor injection for services

Always run build command after changes to verify no compilation errors, particularly with AllIcons references.