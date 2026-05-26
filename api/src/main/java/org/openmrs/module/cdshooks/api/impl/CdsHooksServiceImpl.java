package org.openmrs.module.cdshooks.api.impl;

import org.openmrs.Allergies;
import org.openmrs.Allergy;
import org.openmrs.AllergyReaction;
import org.openmrs.Patient;
import org.openmrs.api.PatientService;
import org.openmrs.module.cdshooks.api.AllergyMatcher;
import org.openmrs.module.cdshooks.api.CdsHooksService;
import org.openmrs.module.cdshooks.model.AllergyMatch;
import org.openmrs.module.cdshooks.model.CdsHooksRequest;
import org.openmrs.module.cdshooks.model.CdsHooksResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service("cdshooks.CdsHooksService")
public class CdsHooksServiceImpl implements CdsHooksService {

    @Autowired
    private PatientService patientService;

    @Autowired
    private AllergyMatcher matcher;

    @Autowired
    private CdsHooksRequestParser parser;

    @Autowired
    private SnomedMappingExtractor snomedExtractor;

    @Autowired
    private SeverityMapper severityMapper;

    @Override
    public CdsHooksResponse evaluateDrugAllergy(CdsHooksRequest request) {
        CdsHooksResponse response = new CdsHooksResponse();

        String patientUuid = parser.extractPatientUuid(request);
        List<AllergyMatcher.DrugInput> drugs = parser.extractDrugs(request);
        if (patientUuid == null || drugs.isEmpty()) {
            return response;
        }

        Patient patient = patientService.getPatientByUuid(patientUuid);
        if (patient == null) {
            return response;
        }

        List<AllergyMatcher.AllergyInput> allergyInputs = toAllergyInputs(patientService.getAllergies(patient));
        if (allergyInputs.isEmpty()) {
            return response;
        }

        List<AllergyMatch> allMatches = new ArrayList<>();
        for (AllergyMatcher.DrugInput drug : drugs) {
            allMatches.addAll(matcher.match(drug, allergyInputs));
        }

        response.setCards(allMatches.stream().map(this::toCard).collect(Collectors.toList()));
        return response;
    }

    private List<AllergyMatcher.AllergyInput> toAllergyInputs(Allergies allergies) {
        List<AllergyMatcher.AllergyInput> inputs = new ArrayList<>();
        if (allergies == null) return inputs;
        for (Allergy allergy : allergies) {
            if (allergy.getAllergen() == null || allergy.getAllergen().getCodedAllergen() == null) {
                continue; // free-text allergies cannot be matched algorithmically
            }
            List<String> sctids = snomedExtractor.extract(allergy.getAllergen().getCodedAllergen());
            if (sctids.isEmpty()) continue;

            inputs.add(new AllergyMatcher.AllergyInput(
                    allergy.getAllergen().getCodedAllergen().getDisplayString(),
                    sctids,
                    severityMapper.map(allergy.getSeverity()),
                    firstReactionDisplay(allergy)));
        }
        return inputs;
    }

    private static String firstReactionDisplay(Allergy allergy) {
        List<AllergyReaction> reactions = allergy.getReactions();
        if (reactions == null || reactions.isEmpty()) return null;
        AllergyReaction r = reactions.get(0);
        if (r.getReaction() != null) return r.getReaction().getDisplayString();
        return r.getReactionNonCoded();
    }

    private CdsHooksResponse.Card toCard(AllergyMatch m) {
        CdsHooksResponse.Card card = new CdsHooksResponse.Card();
        card.uuid = UUID.randomUUID().toString();
        card.summary = "⚠ " + m.getAllergenDisplay()
                + (m.getMatchType() == AllergyMatch.MatchType.CLASS ? " (class match)" : "");
        card.detail = m.getExplanation()
                + (m.getReaction() != null ? "\n\nRecorded reaction: " + m.getReaction() : "");
        card.indicator = SeverityMapper.toCardIndicator(m.getSeverity());
        CdsHooksResponse.Card.Source source = new CdsHooksResponse.Card.Source();
        source.label = "OpenMRS Drug-Allergy Alert";
        card.source = source;
        return card;
    }
}
