# OpenMRS CDS Hooks Module

Exposes OpenMRS as a [CDS-Hooks 2.0](https://cds-hooks.hl7.org/2.0/) service host.
The first bundled service is a **drug-allergy alert** that warns prescribers
when an ordered drug conflicts with the patient's recorded allergies
(ingredient or class match via SNOMED CT).

## Architecture

```
Frontend (esm-patient-chart)
   │
   │ POST /ws/cds-services/drug-allergy
   ▼
CdsServicesController  ──>  CdsHooksService  ──>  SubsumptionClient
                                                       │
                                                       ▼ FHIR $lookup / $subsumes
                                                  Snowstorm (SNOMED CT)
```

The matching algorithm bridges three SNOMED hierarchies:

- **Allergen findings** (e.g., "Allergy to penicillin") via `Causative agent` (SCTID 246075003) → substance(s)
- **Drug products** (e.g., "Amoxicillin-containing product") via `Has active ingredient` (SCTID 127489000) → substance(s)
- Substance × substance `$subsumes` for ingredient and class matches

See [`docs/DESIGN.md`](docs/DESIGN.md) for the full design proposal and [`docs/IMPLEMENTATION_NOTES.md`](docs/IMPLEMENTATION_NOTES.md) for contributor notes (local setup, SNOMED modeling findings, and OpenMRS-platform gotchas).

## Module structure

```
.
├── api/                          # Service layer
│   ├── src/main/java/.../cdshooks/
│   │   ├── api/                  # Service interfaces (CdsHooksService, AllergyMatcher)
│   │   ├── api/impl/             # Service + matcher impl, request parser, severity/audit
│   │   ├── client/               # Snowstorm FHIR client + TTL cache
│   │   ├── terminology/          # Pluggable subsumption backends (Snowstorm / reference-map)
│   │   ├── model/                # CDS-Hooks request/response DTOs
│   │   └── CdsHooksConstants.java
│   └── src/main/resources/moduleApplicationContext.xml
├── omod/                         # Web layer
│   └── src/main/
│       ├── java/.../cdshooks/
│       │   ├── CdsHooksActivator.java
│       │   └── web/
│       │       ├── servlet/CdsServicesServlet.java   # CDS-Hooks discovery + invocation
│       │       └── filter/ForwardingFilter.java      # /ws/cds-services URL + bearer auth
│       └── resources/config.xml
├── frontend/esm-cdshooks-app/    # ESM micro-frontend (drug-allergy alert extension)
└── e2e/                          # Playwright end-to-end tests
```

## Build

Requires Java 11+ and Maven 3.6+. Build with:

```bash
mvn clean install
```

The OMOD will be at `omod/target/cdshooks-1.0.0-SNAPSHOT.omod`.

## Local development

The `dev/` directory holds Docker Compose stacks for running the module against
a real OpenMRS RefApp. These reference the official published OpenMRS images;
nothing is bundled in this repo.

```bash
# Full OpenMRS RefApp 3 stack (gateway + frontend + backend + MariaDB)
docker compose -p cdshooks-dev -f dev/docker-compose.yml up -d
# Frontend:    http://localhost:8081/openmrs/spa
# REST/FHIR:   http://localhost:8081/openmrs/ws/...
# Credentials: admin / Admin123
```

After building, deploy the OMOD into the running backend:

```bash
docker cp omod/target/cdshooks-omod-1.0.0-SNAPSHOT.omod \
  cdshooks-dev-backend-1:/openmrs/data/modules/cdshooks-1.0.0-SNAPSHOT.omod
docker restart cdshooks-dev-backend-1
```

`dev/docker-compose.snowstorm.yml` brings up a local Snowstorm terminology
server for offline or write-access use; see its header for SNOMED release
loading. See [`docs/IMPLEMENTATION_NOTES.md`](docs/IMPLEMENTATION_NOTES.md) for
the full walkthrough.

## Configuration

Global properties:

| Property | Default | Description |
|---|---|---|
| `cdshooks.snowstormUrl` | `https://tx.fhir.org/r4` | Base FHIR URL of the SNOMED terminology server |
| `cdshooks.snomedSystem` | `http://snomed.info/sct` | FHIR system URI for SNOMED CT codes |
| `cdshooks.terminologyBackend` | `snowstorm` | Source for parent-child / subsumption lookups: `snowstorm` (live FHIR), `referenceMap` (local `concept_reference_term_map`, e.g. RxClass NUI/CUI edges), or `both`. See [docs/REFERENCE_MAP_BACKEND.md](docs/REFERENCE_MAP_BACKEND.md). |
| `cdshooks.cacheTtlSeconds` | `3600` | TTL for cached terminology lookups |

## Compatibility

- OpenMRS Platform 2.6+
- FHIR2 module 2.4+
- SNOMED CT (any version exposing the `Causative agent` and `Has active ingredient` attributes — i.e. modern editions)
