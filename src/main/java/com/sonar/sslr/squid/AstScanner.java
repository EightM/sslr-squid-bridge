/*
 * SSLR Squid Bridge
 * Copyright (C) 2010 SonarSource
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package com.sonar.sslr.squid;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.sonar.sslr.api.AstNode;
import com.sonar.sslr.api.CommentAnalyser;
import com.sonar.sslr.api.Grammar;
import com.sonar.sslr.api.RecognitionException;
import com.sonar.sslr.impl.Parser;
import com.sonar.sslr.impl.ast.AstWalker;
import com.sonar.sslr.squid.checks.AbstractParseErrorCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.squid.api.AnalysisException;
import org.sonar.squid.api.SourceCodeSearchEngine;
import org.sonar.squid.api.SourceCodeTreeDecorator;
import org.sonar.squid.api.SourceProject;
import org.sonar.squid.indexer.SquidIndex;
import org.sonar.squid.measures.MetricDef;

import java.io.File;
import java.util.Collection;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class AstScanner<G extends Grammar> {

  private static final Logger LOG = LoggerFactory.getLogger(AstScanner.class);

  private final List<SquidAstVisitor<G>> visitors;
  private final Parser<G> parser;
  private final SquidAstVisitorContextImpl<G> context;

  private final SquidIndex indexer = new SquidIndex();
  private final MetricDef[] metrics;
  private final MetricDef filesMetric;

  protected AstScanner(Builder<G> builder) {
    this.visitors = Lists.newArrayList(builder.visitors);
    this.parser = builder.baseParser;
    this.context = builder.context;

    this.context.setGrammar(parser.getGrammar());
    this.context.getProject().setSourceCodeIndexer(indexer);
    this.context.setCommentAnalyser(builder.commentAnalyser);
    this.metrics = builder.metrics;
    this.filesMetric = builder.filesMetric;
    indexer.index(context.getProject());
  }

  public SourceCodeSearchEngine getIndex() {
    return indexer;
  }

  public void scanFile(File file) {
    scanFiles(ImmutableList.of(file));
  }

  public void scanFiles(Collection<File> files) {
    for (SquidAstVisitor<? extends Grammar> visitor : visitors) {
      visitor.init();
    }

    AstWalker astWalker = new AstWalker(visitors);

    for (File file : files) {
      Exception parseException = null;
      AstNode ast = null;
      try {
        ast = parser.parse(file);
      } catch (Exception e) {
        parseException = e;
        LOG.error("Unable to parse file: " + file.getAbsolutePath(), e);
      }

      try {
        context.setFile(file, filesMetric);
        if (parseException == null) {
          astWalker.walkAndVisit(ast);
        } else {
          // process parse error
          for (SquidAstVisitor<? extends Grammar> visitor : visitors) {
            visitor.visitFile(ast);
          }
          for (SquidAstVisitor<? extends Grammar> visitor : visitors) {
            if (visitor instanceof AbstractParseErrorCheck) {
              if (parseException instanceof RecognitionException) {
                ((AbstractParseErrorCheck) visitor).processRecognitionException((RecognitionException) parseException);
              } else {
                ((AbstractParseErrorCheck) visitor).processException(parseException);
              }
            }
          }
          for (SquidAstVisitor<? extends Grammar> visitor : visitors) {
            visitor.leaveFile(ast);
          }
        }
        context.popTillSourceProject();
      } catch (Exception e) {
        throw new AnalysisException("Unable to analyze file: " + file.getAbsolutePath(), e);
      }
    }

    for (SquidAstVisitor<? extends Grammar> visitor : visitors) {
      visitor.destroy();
    }

    decorateSquidTree();
  }

  protected void decorateSquidTree() {
    if (metrics != null && metrics.length > 0) {
      SourceProject project = context.getProject();
      SourceCodeTreeDecorator decorator = new SourceCodeTreeDecorator(project);
      decorator.decorateWith(metrics);
    }
  }

  public static <G extends Grammar> Builder<G> builder(SquidAstVisitorContextImpl<G> context) {
    return new Builder<G>(context);
  }

  public static class Builder<G extends Grammar> {

    private Parser<G> baseParser;
    private final List<SquidAstVisitor<G>> visitors = Lists.newArrayList();
    private final SquidAstVisitorContextImpl<G> context;
    private CommentAnalyser commentAnalyser;
    private MetricDef[] metrics;
    private MetricDef filesMetric;

    public Builder(SquidAstVisitorContextImpl<G> context) {
      checkNotNull(context, "context cannot be null");
      this.context = context;
    }

    public Builder<G> setBaseParser(Parser<G> baseParser) {
      checkNotNull(baseParser, "baseParser cannot be null");
      this.baseParser = baseParser;
      return this;
    }

    public Builder<G> setCommentAnalyser(CommentAnalyser commentAnalyser) {
      checkNotNull(commentAnalyser, "commentAnalyser cannot be null");
      this.commentAnalyser = commentAnalyser;
      return this;
    }

    public Builder<G> withSquidAstVisitor(SquidAstVisitor<G> visitor) {
      checkNotNull(visitor, "visitor cannot be null");
      visitor.setContext(context);
      visitors.add(visitor);
      return this;
    }

    public Builder<G> withMetrics(MetricDef... metrics) {
      for (MetricDef metric : metrics) {
        checkNotNull(metric, "metrics cannot be null");
      }
      this.metrics = metrics;
      return this;
    }

    public Builder<G> setFilesMetric(MetricDef filesMetric) {
      checkNotNull(filesMetric, "filesMetric cannot be null");
      this.filesMetric = filesMetric;
      return this;
    }

    public AstScanner<G> build() {
      checkState(baseParser != null, "baseParser must be set");
      checkState(commentAnalyser != null, "commentAnalyser must be set");
      checkState(filesMetric != null, "filesMetric must be set");
      return new AstScanner<G>(this);
    }
  }

}
