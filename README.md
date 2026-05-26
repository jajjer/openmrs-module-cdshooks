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

See `docs/DESIGN.md` for the full design proposal and `docs/SPIKE_JOURNAL.md` for the spike journey.

## Module structure

```
.
├── api/                      # Service layer
│   └── src/main/java/org/openmrs/module/cdshooks/
│       ├── api/              # Service interface
│       ├── api/impl/         # Service implementation
│       ├── client/           # Snowstorm FHIR client (pending)
│       ├── model/            # CDS-Hooks request/response DTOs
│       ├── CdsHooksActivator.java
│       └── CdsHooksConstants.java
└── omod/                     # Web layer
    └── src/main/
        ├── java/.../web/controller/
        │   └── CdsServicesController.java
        └── resources/
            ├── config.xml
            └── webModuleApplicationContext.xml
```

## Build

Requires Java 11+ and Maven 3.6+. Build with:

```bash
mvn clean install
```

The OMOD will be at `omod/target/cdshooks-1.0.0-SNAPSHOT.omod`.

## Configuration

Global properties:

| Property | Default | Description |
|---|---|---|
| `cdshooks.snowstormUrl` | `https://tx.fhir.org/r4` | Base FHIR URL of the SNOMED terminology server |
| `cdshooks.snomedSystem` | `http://snomed.info/sct` | FHIR system URI for SNOMED CT codes |
| `cdshooks.cacheTtlSeconds` | `3600` | TTL for cached terminology lookups |

## Compatibility

- OpenMRS Platform 2.6+
- FHIR2 module 2.4+
- SNOMED CT (any version exposing the `Causative agent` and `Has active ingredient` attributes — i.e. modern editions)
