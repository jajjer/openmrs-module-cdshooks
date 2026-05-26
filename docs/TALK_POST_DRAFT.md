# Draft reply for the OpenMRS Talk thread

> Copy-paste this into the existing "1:1 Allergy/Rx should trigger warning" thread.

---

Quick update on this — I took the architecture from my earlier reply and built it as a spike to make sure the pieces fit together. End-to-end working against an OpenMRS 2.8.4 instance: the discovery and `medication-prescribe` hook endpoints respond at the spec-compliant `/openmrs/ws/cds-services` URL, and the matching algorithm bridges three SNOMED hierarchies (allergen *finding* → substance via `Causative agent`; drug *product* → substance via `Has active ingredient`) to correctly identify Amoxicillin as a penicillin.

Sample warning Card from the live demo against a patient with a recorded `Allergy to penicillin` (Severe / Hepatotoxicity):

```json
{
  "summary": "⚠ Allergy to penicillin (class match)",
  "detail":  "Amoxicillin contains Amoxicillin (substance), which is a Penicillin —
              the causative-agent class for Allergy to penicillin.
              Recorded reaction: Hepatotoxicity",
  "indicator": "critical"
}
```

Code + design doc + spike journey are at **https://github.com/jajjer/openmrs-module-cdshooks**. The repo is currently private; happy to flip it public if the squad would prefer.

**Three things the spike surfaced that I'd love community input on:**

1. **CIEL coverage of SNOMED is partial.** "Allergy to penicillin" (CIEL 149071) has a SNOMED finding mapping, but "Amoxicillin" (CIEL 71160) has no SNOMED reference term in the running RefApp at all. The matching algorithm relies on SNOMED on both sides. Who owns getting commonly-ordered drug concepts mapped — CIEL team, the distro, individual implementers? This is the real schedule risk for the feature.

2. **The class-match algorithm needs a "stop-at-clinically-meaningful" filter.** When the allergen finding has multiple `Causative agent` values (e.g., the penicillin allergy finding lists "Pharmaceutical / biologic product" alongside "Penicillin"), the algorithm currently returns a card for each match. The "Pharmaceutical / biologic product" match is technically correct but not clinically useful. Open question: filter by hierarchy depth, by hand-curated allow-list, or both?

3. **`@akanter` — would you have time to review the design doc's Section 3 (Clinical & Terminology Background) before this goes any further?** I'm summarizing your direction from this thread and want to make sure I have it right before committing more code. Particularly: is the `Causative agent` traversal pattern the right SNOMED-native answer, or did you mean something different?

Also tagging `@veronica` — the warning render matches what you described (inline on order add, severity-tiered indicator). For the frontend extension I'm planning to register into `order-item-additional-info-slot` in `esm-patient-medications-app`, which the host module already exposes. Sound right?

Thanks again to both of you for the framing that made this possible — the spike turned out to be a much more interesting problem than the ticket title suggested, and I learned a lot.
