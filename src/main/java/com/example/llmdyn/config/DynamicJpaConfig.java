package com.example.llmdyn.config;


import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
@Configuration
@EnableJpaRepositories(
        basePackages = "com.example.llmdyn.dynamic",
        entityManagerFactoryRef = "dynamicEntityManagerFactory",
        transactionManagerRef = "dynamicTransactionManager"
)
public class DynamicJpaConfig {

    @Bean(name = "dynamicEntityManagerFactory")
    @Primary
    public LocalContainerEntityManagerFactoryBean dynamicEntityManagerFactory(
            DataSource dataSource,
            JpaVendorAdapter jpaVendorAdapter,
            Environment env) {

        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(dataSource);
        emf.setJpaVendorAdapter(jpaVendorAdapter);
        emf.setPersistenceUnitName("dynamic");
        emf.setPackagesToScan(new String[0]);

        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.hbm2ddl.auto", env.getProperty("spring.jpa.hibernate.ddl-auto", "update"));

        String dialect = env.getProperty("spring.jpa.properties.hibernate.dialect");
        if (dialect != null) {
            properties.put("hibernate.dialect", dialect);
        }

        emf.setJpaPropertyMap(properties);

        return emf;
    }

    @Bean(name = "dynamicTransactionManager")
    public PlatformTransactionManager dynamicTransactionManager(
            @Qualifier("dynamicEntityManagerFactory") LocalContainerEntityManagerFactoryBean dynamicEntityManagerFactory) {
        return new JpaTransactionManager(dynamicEntityManagerFactory.getObject());
    }
}