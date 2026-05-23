package com.ontology.lite;

import com.ontology.lite.controller.OntologyController;
import com.ontology.lite.mapper.MyBatisOntologyDataMapper;
import com.ontology.lite.mapper.OntologyDataMapper;
import com.ontology.lite.mapper.PersistentPlatformStateMapper;
import com.ontology.lite.service.ComputeResourceSchemaInitializer;
import com.ontology.lite.service.OntologyPlatformService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class SsmArchitectureTest {
    @Test
    void backendUsesControllerServiceMapperLayers() {
        assertThat(OntologyController.class.getPackageName()).isEqualTo("com.ontology.lite.controller");
        assertThat(OntologyPlatformService.class.getPackageName()).isEqualTo("com.ontology.lite.service");
        assertThat(OntologyDataMapper.class.getPackageName()).isEqualTo("com.ontology.lite.mapper");
        assertThat(MyBatisOntologyDataMapper.class.getPackageName()).isEqualTo("com.ontology.lite.mapper");
        assertThat(PersistentPlatformStateMapper.class.getPackageName()).isEqualTo("com.ontology.lite.mapper");
        assertThat(ComputeResourceSchemaInitializer.class.getPackageName()).isEqualTo("com.ontology.lite.service");
    }

    @Test
    void serviceDependsOnMapperLayerByConstructorInjection() {
        boolean hasMapperConstructor = Arrays.stream(OntologyPlatformService.class.getDeclaredConstructors())
            .anyMatch(constructor -> Arrays.asList(constructor.getParameterTypes()).contains(OntologyDataMapper.class));

        assertThat(hasMapperConstructor).isTrue();
    }

    @Test
    void runtimeMapperUsesMyBatisPersistence() {
        assertThat(OntologyDataMapper.class.isAssignableFrom(MyBatisOntologyDataMapper.class)).isTrue();
        assertThat(PersistentPlatformStateMapper.class.isAnnotationPresent(org.apache.ibatis.annotations.Mapper.class)).isTrue();
    }
}
