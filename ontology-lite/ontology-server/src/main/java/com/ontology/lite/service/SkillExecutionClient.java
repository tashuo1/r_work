package com.ontology.lite.service;

import com.ontology.lite.model.PlatformModels.DataStandardSkillRequest;
import com.ontology.lite.model.PlatformModels.DataStandardSkillResult;

public interface SkillExecutionClient {
    DataStandardSkillResult generateDataStandards(DataStandardSkillRequest request);
}
