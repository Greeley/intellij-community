/*
 * Copyright 2000-2007 JetBrains s.r.o.
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

package org.jetbrains.plugins.groovy.annotator;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.lang.annotation.Annotation;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.groovy.GroovyBundle;
import org.jetbrains.plugins.groovy.annotator.intentions.OuterImportsActionCreator;
import org.jetbrains.plugins.groovy.lang.lexer.GroovyTokenTypes;
import org.jetbrains.plugins.groovy.lang.psi.GrReferenceElement;
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElement;
import org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil;
import org.jetbrains.plugins.groovy.lang.psi.api.GroovyResolveResult;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrField;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrVariable;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrAssignmentExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.GrTypeDefinition;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.bodies.GrClassBody;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMethod;
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrTypeOrPackageReferenceElement;
import org.jetbrains.plugins.groovy.lang.psi.impl.statements.expressions.arithmetic.TypesUtil;
import org.jetbrains.plugins.groovy.lang.psi.impl.statements.clauses.GrTraditionalForClauseImpl;

/**
 * @author ven
 */
public class GroovyAnnotator implements Annotator {
  private static final Logger LOG = Logger.getInstance("org.jetbrains.plugins.groovy.annotator.GroovyAnnotator");

  private GroovyAnnotator() {
  }

  public static final GroovyAnnotator INSTANCE = new GroovyAnnotator();

  public void annotate(PsiElement element, AnnotationHolder holder) {
    if (element instanceof GrTypeOrPackageReferenceElement) {
      checkReferenceElement(holder, (GrTypeOrPackageReferenceElement) element);
    } else if (element instanceof GrReferenceExpression) {
      checkReferenceExpression(holder, (GrReferenceExpression) element);
    } else if (element instanceof GrTypeDefinition) {
      checkTypeDefinition(holder, (GrTypeDefinition) element);
    } else if (element instanceof GrVariable) {
      checkVariable(holder, (GrVariable) element);
    } else if (element instanceof GrAssignmentExpression) {
      checkAssignmentExpression((GrAssignmentExpression)element, holder);
    } else if (element instanceof GrTraditionalForClauseImpl) {
      forbidTraditionalForClause(((GrTraditionalForClauseImpl) element), holder);
    }
  }

  private void checkAssignmentExpression(GrAssignmentExpression assignment, AnnotationHolder holder) {
    IElementType opToken = assignment.getOperationToken();
    if (opToken == GroovyTokenTypes.mASSIGN) {
      GrExpression lValue = assignment.getLValue();
      GrExpression rValue = assignment.getRValue();
      if (lValue != null && rValue != null) {
        PsiType lType = lValue.getType();
        PsiType rType = rValue.getType();
        if (lType != null && rType != null) {
          checkAssignability(holder, lType, rType, rValue);
        }
      }
    }
  }

  private void forbidTraditionalForClause(GrTraditionalForClauseImpl clause, AnnotationHolder holder){
    holder.createErrorAnnotation(clause, "\"Traditional\" for-loop clause is not implemented in Groovy yet");
  }

  private void checkVariable(AnnotationHolder holder, GrVariable variable) {
    PsiType varType = variable.getType();
    GrExpression initializer = variable.getInitializerGroovy();
    if (initializer != null) {
      PsiType rType = initializer.getType();
      if (rType != null) {
        checkAssignability(holder, varType, rType, initializer);
      }
    }
  }

  private void checkAssignability(AnnotationHolder holder, @NotNull PsiType lType, @NotNull PsiType rType, GroovyPsiElement element) {
    if (!TypesUtil.isAssignable(lType, rType, element.getManager(), element.getResolveScope())) {
      holder.createWarningAnnotation(element, GroovyBundle.message("cannot.assign", rType.getInternalCanonicalText(), lType.getInternalCanonicalText()));
    }
  }

  private void checkTypeDefinition(AnnotationHolder holder, GrTypeDefinition typeDefinition) {
    if (typeDefinition.getParent() instanceof GrClassBody) {
      holder.createErrorAnnotation(typeDefinition.getNameIdentifierGroovy(), "Inner classes are not supported in Groovy");
    }
  }

  private void checkReferenceExpression(AnnotationHolder holder, final GrReferenceExpression refExpr) {
    GroovyResolveResult resolveResult = refExpr.advancedResolve();
    PsiElement element = resolveResult.getElement();
    if (element != null) {
      if (!resolveResult.isAccessible()) {
        String message = GroovyBundle.message("cannot.access", refExpr.getReferenceName());
        holder.createWarningAnnotation(refExpr, message);
      } else if (element instanceof PsiMethod && element.getUserData(GrMethod.BUILDER_METHOD) == null) {
        PsiType[] argumentTypes = PsiUtil.getArgumentTypes(refExpr);
        if (argumentTypes != null && !PsiUtil.isApplicable(argumentTypes, (PsiMethod)element)) {
          GroovyPsiElement elementToHighlight = PsiUtil.getArgumentsElement(refExpr);
          LOG.assertTrue(elementToHighlight != null);
          //todo more specific error message
          String message = GroovyBundle.message("cannot.apply.method", refExpr.getReferenceName());
          holder.createWarningAnnotation(elementToHighlight, message);
        }
      }
    } else {
      if (refExpr.getQualifierExpression() == null) {
        PsiModifierListOwner method = PsiTreeUtil.getParentOfType(refExpr, GrMethod.class, GrField.class); //todo for static fields as well
        if (method != null && method.hasModifierProperty(PsiModifier.STATIC)) {
          Annotation annotation = holder.createErrorAnnotation(refExpr, GroovyBundle.message("cannot.resolve", refExpr.getReferenceName()));
          annotation.setHighlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL);
        } else {
          if (refExpr.getParent() instanceof GrReferenceExpression) {
            Annotation annotation = holder.createWarningAnnotation(refExpr, GroovyBundle.message("cannot.resolve", refExpr.getReferenceName()));
            registerAddImportFixes(refExpr, annotation);
          }
        }
      }
    }
  }

  private void checkReferenceElement(AnnotationHolder holder, final GrTypeOrPackageReferenceElement refElement) {
    if (refElement.getReferenceName() != null) {
      GroovyResolveResult resolveResult = refElement.advancedResolve();
      final PsiElement resolved = resolveResult.getElement();
      if (resolved == null) {
        String message = GroovyBundle.message("cannot.resolve", refElement.getReferenceName());

        // Register quickfix
        final Annotation annotation = holder.createErrorAnnotation(refElement, message);
        registerAddImportFixes(refElement, annotation);
        annotation.setHighlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL);
      } else if (!resolveResult.isAccessible()) {
        String message = GroovyBundle.message("cannot.access", refElement.getReferenceName());
        holder.createErrorAnnotation(refElement, message);
      }
    }
  }

  private void registerAddImportFixes(GrReferenceElement refElement, Annotation annotation) {
    final IntentionAction[] actions = OuterImportsActionCreator.getOuterImportFixes(refElement, refElement.getProject());
    for (IntentionAction action : actions) {
      annotation.registerFix(action);
    }
  }
}

