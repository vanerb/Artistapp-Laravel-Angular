# ArtistApp Java backend (migration in progress)

This module is being added alongside the existing Laravel backend. Laravel remains untouched.

## Requirements
- Java 21
- MySQL with the existing `vrb_artistapp` database
- Maven (or use the Maven wrapper if added later)

## Configuration
Set environment variables before running:
- `DB_URL` (default: `jdbc:mysql://localhost:3306/vrb_artistapp?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Europe/Madrid`)
- `DB_USERNAME` (default: `root`)
- `DB_PASSWORD`
- `JWT_SECRET` (use a random secret of at least 32 characters; do not use the development default outside local development)
- `PORT` (default: `8081`)

Run from this directory with `mvn spring-boot:run`.

## Migration status
The initial Spring Boot structure, database connection settings, stateless JWT security filter, and CORS configuration are in place. API modules and compatibility endpoints are being migrated incrementally. Do not switch Angular to this backend until the required endpoints have been implemented and tested. No database migrations are run automatically by this module.