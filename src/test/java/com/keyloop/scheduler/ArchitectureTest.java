package com.keyloop.scheduler;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.keyloop.scheduler.support.IntegrationTest;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.boot.test.context.SpringBootTest;

@AnalyzeClasses(
    packages = "com.keyloop.scheduler",
    importOptions = {ImportOption.DoNotIncludeJars.class})
class ArchitectureTest {

  @ArchTest
  static final ArchRule queryPackagesDoNotDependOnCommandRepositories =
      noClasses()
          .that()
          .resideInAnyPackage("..query..", "..availability..")
          .should()
          .dependOnClassesThat(
              resideInAPackage("..booking..").and(simpleNameEndingWith("Repository")));

  @ArchTest
  static final ArchRule controllersDoNotUseRepositoriesDirectly =
      noClasses()
          .that()
          .haveSimpleNameEndingWith("Controller")
          .should()
          .dependOnClassesThat()
          .haveSimpleNameEndingWith("Repository");

  @ArchTest
  static final ArchRule unitTestsDoNotStartSpring =
      classes()
          .that()
          .haveSimpleNameEndingWith("Test")
          .and()
          .doNotHaveModifier(JavaModifier.ABSTRACT)
          .should()
          .notBeAnnotatedWith(SpringBootTest.class)
          .andShould()
          .notBeAssignableTo(IntegrationTest.class);
}
