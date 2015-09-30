/*
 * Copyright 2015 Nokia Solutions and Networks
 * Licensed under the Apache License, Version 2.0,
 * see license.txt file for details.
 */
package org.robotframework.ide.eclipse.main.plugin.project.build.validation;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Sets.newHashSet;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.robotframework.ide.core.testData.model.table.RobotExecutableRow;
import org.robotframework.ide.core.testData.model.table.TestCaseTable;
import org.robotframework.ide.core.testData.model.table.testCases.TestCase;
import org.robotframework.ide.core.testData.text.read.recognizer.RobotToken;
import org.robotframework.ide.eclipse.main.plugin.model.RobotCasesSection;
import org.robotframework.ide.eclipse.main.plugin.model.RobotKeywordDefinition;
import org.robotframework.ide.eclipse.main.plugin.model.RobotSuiteFile;
import org.robotframework.ide.eclipse.main.plugin.model.locators.ContinueDecision;
import org.robotframework.ide.eclipse.main.plugin.model.locators.KeywordDefinitionLocator;
import org.robotframework.ide.eclipse.main.plugin.model.locators.KeywordDefinitionLocator.KeywordDetector;
import org.robotframework.ide.eclipse.main.plugin.project.build.ProblemPosition;
import org.robotframework.ide.eclipse.main.plugin.project.build.ProblemsReportingStrategy;
import org.robotframework.ide.eclipse.main.plugin.project.build.RobotArtifactsValidator.ModelUnitValidator;
import org.robotframework.ide.eclipse.main.plugin.project.build.RobotProblem;
import org.robotframework.ide.eclipse.main.plugin.project.build.causes.IProblemCause;
import org.robotframework.ide.eclipse.main.plugin.project.build.causes.KeywordsProblem;
import org.robotframework.ide.eclipse.main.plugin.project.build.causes.TestCasesProblem;
import org.robotframework.ide.eclipse.main.plugin.project.library.KeywordSpecification;
import org.robotframework.ide.eclipse.main.plugin.project.library.LibrarySpecification;

import com.google.common.base.Optional;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;

class TestCasesTableValidator implements ModelUnitValidator {

    private final Optional<RobotCasesSection> testCaseSection;

    private final ProblemsReportingStrategy reporter = new ProblemsReportingStrategy();

    TestCasesTableValidator(final Optional<RobotCasesSection> section) {
        this.testCaseSection = section;
    }

    @Override
    public void validate(final IProgressMonitor monitor) throws CoreException {
        if (!testCaseSection.isPresent()) {
            return;
        }
        final RobotSuiteFile suiteModel = testCaseSection.get().getSuiteFile();
        final TestCaseTable casesTable = (TestCaseTable) testCaseSection.get().getLinkedElement();

        reportEmptyCases(suiteModel.getFile(), casesTable);
        reportDuplicatedCases(suiteModel.getFile(), casesTable);
        reportUnkownKeywords(testCaseSection.get().getSuiteFile(), reporter, findExecutableRows());
    }

    private void reportEmptyCases(final IFile file, final TestCaseTable casesTable) {
        for (final TestCase testCase : casesTable.getTestCases()) {
            final RobotToken caseName = testCase.getTestName();
            final IProblemCause cause = TestCasesProblem.EMPTY_CASE;
            reportEmptyExecutableRows(file, reporter, caseName, testCase.getTestExecutionRows(), cause);
        }
    }

    static void reportEmptyExecutableRows(final IFile file, final ProblemsReportingStrategy reporter,
            final RobotToken def, final List<? extends RobotExecutableRow<?>> executables,
            final IProblemCause causeToReport) {
        if (executables.isEmpty()) {
            final String name = def.getText().toString();

            final RobotProblem problem = RobotProblem.causedBy(causeToReport).formatMessageWith(name);
            final ProblemPosition position = new ProblemPosition(def.getLineNumber(),
                    Range.closed(def.getStartOffset(), def.getStartOffset() + name.length()));
            final Map<String, Object> arguments = new HashMap<>();
            arguments.put("name", name);
            reporter.handleProblem(problem, file, position, arguments);
        }
    }

    private void reportDuplicatedCases(final IFile file, final TestCaseTable casesTable) {
        final Set<String> duplicatedNames = newHashSet();

        for (final TestCase case1 : casesTable.getTestCases()) {
            for (final TestCase case2 : casesTable.getTestCases()) {
                if (case1 != case2) {
                    final String case1Name = case1.getTestName().getText().toString();
                    final String case2Name = case2.getTestName().getText().toString();

                    if (case1Name.trim().equalsIgnoreCase(case2Name.trim())) {
                        duplicatedNames.add(case1Name.toLowerCase());
                    }
                }
            }
        }

        for (final TestCase testCase : casesTable.getTestCases()) {
            final RobotToken caseName = testCase.getTestName();
            final String name = caseName.getText().toString();

            if (duplicatedNames.contains(name.toLowerCase())) {
                final RobotProblem problem = RobotProblem.causedBy(TestCasesProblem.DUPLICATED_CASE)
                        .formatMessageWith(name);
                final ProblemPosition position = new ProblemPosition(caseName.getLineNumber(),
                        Range.closed(caseName.getStartOffset(), caseName.getStartOffset() + name.length()));
                final Map<String, Object> additionalArguments = Maps.newHashMap();
                additionalArguments.put("name", name);
                reporter.handleProblem(problem, file, position, additionalArguments);
            }
        }

    }

    private List<RobotExecutableRow<?>> findExecutableRows() {
        final List<RobotExecutableRow<?>> executables = newArrayList();
        final TestCaseTable testCaseTable = (TestCaseTable) testCaseSection.get().getLinkedElement();
        for (final TestCase testCase : testCaseTable.getTestCases()) {
            executables.addAll(testCase.getTestExecutionRows());
        }
        return executables;
    }

    static void reportUnkownKeywords(final RobotSuiteFile robotSuiteFile, final ProblemsReportingStrategy reporter,
            final List<RobotExecutableRow<?>> executables) {
        final Set<String> names = collectAccessibleKeywordNames(robotSuiteFile);

        for (final RobotExecutableRow<?> executable : executables) {
            final RobotToken keywordName = getKeywordNameToken(executable);
            if (keywordName == null) {
                continue;
            }
            final String name = keywordName.getText().toString();
            if (!names.contains(name)) {
                final RobotProblem problem = RobotProblem.causedBy(KeywordsProblem.UNKNOWN_KEYWORD)
                        .formatMessageWith(name);
                final ProblemPosition position = new ProblemPosition(keywordName.getLineNumber(),
                        Range.closed(keywordName.getStartOffset(), keywordName.getStartOffset() + name.length()));
                final Map<String, Object> additionalArguments = Maps.newHashMap();
                additionalArguments.put("name", name);
                reporter.handleProblem(problem, robotSuiteFile.getFile(), position, additionalArguments);
            }
        }
    }

    private static Set<String> collectAccessibleKeywordNames(final RobotSuiteFile robotSuiteFile) {
        final Set<String> names = new HashSet<>();
        new KeywordDefinitionLocator(robotSuiteFile, false)
                .locateKeywordDefinition(new KeywordDetector() {

                    @Override
                    public ContinueDecision libraryKeywordDetected(final LibrarySpecification libSpec,
                            final KeywordSpecification kwSpec) {
                        names.add(kwSpec.getName());
                        return ContinueDecision.CONTINUE;
                    }

                    @Override
                    public ContinueDecision keywordDetected(final RobotSuiteFile file,
                            final RobotKeywordDefinition keyword) {
                        names.add(keyword.getName());
                        return ContinueDecision.CONTINUE;
                    }
                });
        return names;
    }

    private static RobotToken getKeywordNameToken(final RobotExecutableRow<?> executable) {
        final List<RobotToken> candidates = newArrayList();
        candidates.add(executable.getAction());
        candidates.addAll(executable.getArguments());

        boolean isFirst = true;
        for (final RobotToken token : candidates) {
            if (Pattern.matches("^[@$&%]\\{[^\\{]+\\} *=? *$", token.getText().toString())) {
                isFirst = false;
                continue;
            } else if (isFirst && "\\".equals(token.getText().toString().trim())) {
                isFirst = false;
                continue;
            } else if (isFirst && ": FOR".equals(token.getText().toString().trim())) {
                return null;
            }
            return token;
        }
        return null;
    }
}
