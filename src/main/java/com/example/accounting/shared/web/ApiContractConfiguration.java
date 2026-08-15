package com.example.accounting.shared.web;

import io.swagger.v3.oas.models.media.StringSchema;
import java.math.BigDecimal;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

@Configuration(proxyBeanMethods = false)
public class ApiContractConfiguration {

    static {
        SpringDocUtils.getConfig().replaceWithSchema(BigDecimal.class, new StringSchema());
    }

    @Bean
    JsonMapperBuilderCustomizer decimalJsonCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("decimal-as-string");
            module.addSerializer(BigDecimal.class, new ToStringSerializer(BigDecimal.class));
            builder.addModule(module);
        };
    }
}
