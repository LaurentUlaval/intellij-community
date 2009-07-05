/*
 * Copyright 2009 Bas Leijdekkers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.siyeh.ig.performance;

import com.intellij.codeInsight.CodeInsightUtilBase;
import com.intellij.codeInsight.template.Expression;
import com.intellij.codeInsight.template.Template;
import com.intellij.codeInsight.template.TemplateBuilder;
import com.intellij.codeInsight.template.TemplateManager;
import com.intellij.codeInsight.template.impl.MacroCallNode;
import com.intellij.codeInsight.template.macro.SuggestVariableNameMacro;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.IncorrectOperationException;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashSet;

public class DynamicRegexReplaceableByCompiledPatternInspection
        extends BaseInspection {

    @NonNls
    private static final Collection<String> regexMethodNames =
            new HashSet(4);

    static{
        regexMethodNames.add("matches");
        regexMethodNames.add("replaceFirst");
        regexMethodNames.add("replaceAll");
        regexMethodNames.add("split");
    }

    @Override
    @Nls
    @NotNull
    public String getDisplayName() {
        return InspectionGadgetsBundle.message(
                "dynamic.regex.replaceable.by.compiled.pattern.display.name");
    }

    @Override
    @NotNull
    protected String buildErrorString(Object... infos) {
        return InspectionGadgetsBundle.message(
                "dynamic.regex.replaceable.by.compiled.pattern.problem.descriptor");
    }

    @Override
    protected boolean buildQuickFixesOnlyForOnTheFlyErrors() {
        return true;
    }

    @Override
    protected InspectionGadgetsFix buildFix(Object... infos) {
        return new DynamicRegexReplaceableByCompiledPatternFix();
    }

    private static class DynamicRegexReplaceableByCompiledPatternFix
            extends InspectionGadgetsFix {

        @NotNull
        public String getName() {
            return InspectionGadgetsBundle.message(
                    "dynamic.regex.replaceable.by.compiled.pattern.quickfix");
        }

        @Override
        protected void doFix(Project project, ProblemDescriptor descriptor)
                throws IncorrectOperationException {
            final PsiElement element = descriptor.getPsiElement();
            final PsiClass aClass = PsiTreeUtil.getParentOfType(element,
                    PsiClass.class);
            if (aClass == null) {
                return;
            }
            final PsiElement parent = element.getParent();
            if (!(parent instanceof PsiReferenceExpression)) {
                return;
            }
            final PsiReferenceExpression methodExpression =
                    (PsiReferenceExpression)parent;
            final PsiElement grandParent = methodExpression.getParent();
            if (!(grandParent instanceof PsiMethodCallExpression)) {
                return;
            }
            final PsiMethodCallExpression methodCallExpression =
                    (PsiMethodCallExpression)grandParent;
            final PsiExpressionList list =
                    methodCallExpression.getArgumentList();
            final PsiExpression[] expressions = list.getExpressions();
            final StringBuilder fieldText =
                    new StringBuilder(
                            "private static final Pattern PATTERN = " +
                                    "Pattern.compile(");
            final int expressionsLength = expressions.length;
            System.out.println("expressionsLength: " + expressionsLength);
            if (expressionsLength > 0) {
                fieldText.append(expressions[0].getText());
            }
            fieldText.append(");");
            final PsiElementFactory factory =
                    JavaPsiFacade.getElementFactory(project);
            final PsiField newField =
                    factory.createFieldFromText(fieldText.toString(), element);
            final PsiElement field = aClass.add(newField);

            final StringBuilder expressionText = new StringBuilder("PATTERN.");
            expressionText.append(methodExpression.getReferenceName());
            expressionText.append('(');
            final PsiExpression qualifier =
                    methodExpression.getQualifierExpression();
            if (qualifier != null) {
                expressionText.append(qualifier.getText());
            } else {
                expressionText.append("this");
            }
            for (int i = 1; i < expressionsLength; i++) {
                expressionText.append(',');
                expressionText.append(expressions[i].getText());
            }
            expressionText.append(')');
            final PsiExpression newExpression =
                    factory.createExpressionFromText(expressionText.toString(),
                            element);
            PsiMethodCallExpression newMethodCallExpression =
                    (PsiMethodCallExpression)methodCallExpression.replace(
                            newExpression);
            newMethodCallExpression =
                    CodeInsightUtilBase.forcePsiPostprocessAndRestoreElement(
                            newMethodCallExpression);
            final PsiReferenceExpression reference = (PsiReferenceExpression)
                    newMethodCallExpression.getMethodExpression()
                            .getQualifierExpression();
            showRenameTemplate(aClass, (PsiNameIdentifierOwner) field,
                    reference);
        }

        private static void showRenameTemplate(PsiElement context,
                                               PsiNameIdentifierOwner element,
                                               PsiReference... references) {
            //context = CodeInsightUtilBase.forcePsiPostprocessAndRestoreElement(
            //        context);
            final Project project = context.getProject();
            final FileEditorManager fileEditorManager =
                    FileEditorManager.getInstance(project);
            final Editor editor = fileEditorManager.getSelectedTextEditor();
            if (editor == null) {
                return;
            }
            final TemplateBuilder builder = new TemplateBuilder(context);
            final Expression macroCallNode = new MacroCallNode(
                    new SuggestVariableNameMacro());
            final PsiElement identifier = element.getNameIdentifier();
            builder.replaceElement(identifier, "PATTERN", macroCallNode, true);
            for (PsiReference reference : references) {
                builder.replaceElement(reference, "PATTERN", "PATTERN",
                        false);
            }
            final Template template = builder.buildInlineTemplate();
            final TextRange textRange = context.getTextRange();
            final int startOffset = textRange.getStartOffset();
            editor.getCaretModel().moveToOffset(startOffset);
            final TemplateManager templateManager =
                    TemplateManager.getInstance(project);
            templateManager.startTemplate(editor, template);
        }
    }

    @Override
    public BaseInspectionVisitor buildVisitor() {
        return new DynamicRegexReplaceableByCompiledPatternVisitor();
    }

    private static class DynamicRegexReplaceableByCompiledPatternVisitor
            extends BaseInspectionVisitor {

        @Override
        public void visitMethodCallExpression(
                PsiMethodCallExpression expression) {
            super.visitMethodCallExpression(expression);
            if (!isCallToRegexMethod(expression)) {
                return;
            }
            registerMethodCallError(expression);
        }


        private static boolean isCallToRegexMethod(
                PsiMethodCallExpression expression) {
            final PsiReferenceExpression methodExpression =
                    expression.getMethodExpression();
            final String name = methodExpression.getReferenceName();
            if(!regexMethodNames.contains(name)){
                return false;
            }
            final PsiExpressionList argumentList = expression.getArgumentList();
            final PsiExpression[] arguments = argumentList.getExpressions();
            for (PsiExpression argument : arguments) {
                if (!PsiUtil.isConstantExpression(argument)) {
                    return false;
                }
            }
            final PsiMethod method = expression.resolveMethod();
            if(method == null){
                return false;
            }
            final PsiClass containingClass = method.getContainingClass();
            if(containingClass == null){
                return false;
            }
            final String className = containingClass.getQualifiedName();
            return "java.lang.String".equals(className);
        }
    }
}