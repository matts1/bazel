// Copyright 2021 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.common.collect.ImmutableCollection;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.LabelConverter;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.packages.Type.ConversionException;
import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code;
import com.google.devtools.build.lib.analysis.config.transitions.BaselineOptionsValue;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.skyframe.RuleConfiguredTargetValue;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.Structure;
import net.starlark.java.spelling.SpellChecker;
import net.starlark.java.syntax.Location;

/**
 * A {@link Tag} whose attribute values have been type-checked against the attribute schema define
 * in the {@link TagClass}.
 */
@StarlarkBuiltin(name = "bazel_module_tag", documented = false)
public class TypeCheckedTag implements Structure {
  private final TagClass tagClass;
  private final Object[] attrValues;
  private final boolean devDependency;

  // The properties below are only used for error reporting.
  private final Location location;
  private final String tagClassName;

  private TypeCheckedTag(
      TagClass tagClass,
      Object[] attrValues,
      boolean devDependency,
      Location location,
      String tagClassName) {
    this.tagClass = tagClass;
    this.attrValues = attrValues;
    this.devDependency = devDependency;
    this.location = location;
    this.tagClassName = tagClassName;
  }

  /** Creates a {@link TypeCheckedTag}. */
  public static @Nullable TypeCheckedTag create(SkyFunction.Environment env, TagClass tagClass, Tag tag, LabelConverter labelConverter)
      throws ExternalDepsException, InterruptedException {
    Object[] attrValues = new Object[tagClass.getAttributes().size()];
    for (Map.Entry<String, Object> attrValue : tag.getAttributeValues().attributes().entrySet()) {
      Integer attrIndex = tagClass.getAttributeIndices().get(attrValue.getKey());
      if (attrIndex == null) {
        throw ExternalDepsException.withMessage(
            Code.BAD_MODULE,
            "in tag at %s, unknown attribute %s provided%s",
            tag.getLocation(),
            attrValue.getKey(),
            SpellChecker.didYouMean(attrValue.getKey(), tagClass.getAttributeIndices().keySet()));
      }
      Attribute attr = tagClass.getAttributes().get(attrIndex);
      Object nativeValue;
      try {
        nativeValue =
            attr.getType().convert(attrValue.getValue(), attr.getPublicName(), labelConverter);
      } catch (ConversionException e) {
        throw ExternalDepsException.withCauseAndMessage(
            Code.BAD_MODULE,
            e,
            "in tag at %s, error converting value for attribute %s",
            tag.getLocation(),
            attr.getPublicName());
      }

      // Check that the value is actually allowed.
      if (attr.checkAllowedValues() && !attr.getAllowedValues().apply(nativeValue)) {
        throw ExternalDepsException.withMessage(
            Code.BAD_MODULE,
            "in tag at %s, the value for attribute %s %s",
            tag.getLocation(),
            attr.getPublicName(),
            attr.getAllowedValues().getErrorReason(nativeValue));
      }

      if (nativeValue instanceof Label) {
        Label platform = null;
        try {
          // TODO: support RBE
          platform = Label.parseCanonical("@@local_config_platform//:host");
        } catch (LabelSyntaxException e) {
          System.out.println("INVALID LABEL");
          System.exit(1);
        }
        BaselineOptionsValue options = (BaselineOptionsValue) env.getValue(BaselineOptionsValue.key(/* afterExecTransition= */ true, /* newPlatform=*/ platform));
        if (options == null) {
          return null;
        }

        ConfiguredTargetKey key = ConfiguredTargetKey.builder()
            .setLabel((Label)nativeValue)
            .setConfigurationKey(BuildConfigurationKey.create(options.toOptions()))
            .build();
        ConfiguredTargetValue configuredTarget = (ConfiguredTargetValue) env.getValue(key);
        if (configuredTarget == null) {
          return null;
        }
        if (configuredTarget instanceof RuleConfiguredTargetValue) {
          nativeValue = ((RuleConfiguredTargetValue)configuredTarget).getConfiguredTarget().getProvidersDictForQuery();
          // TODO: This is currently required to ensure that module_ctx.read succeeds. However, we should be more lazy about this and just do it in module_ctx.read. The problem is that read isn't compatible with restarts at the moment.
          for (ActionAnalysisMetadata action : ((RuleConfiguredTargetValue)configuredTarget).getActions()) {
            for (Artifact output : action.getOutputs()) {
              SkyKey key2 = Artifact.key(output);
              SkyValue result = env.getValue(key2);
              if (result == null) {
                return null;
              }
            }
          }
        }
        // attr.isSatisfiedBy(configuredTarget.)
      }

      attrValues[attrIndex] = Attribute.valueToStarlark(nativeValue);
    }

    // Check that all mandatory attributes have been specified, and fill in default values.
    for (int i = 0; i < attrValues.length; i++) {
      Attribute attr = tagClass.getAttributes().get(i);
      if (attr.isMandatory() && attrValues[i] == null) {
        throw ExternalDepsException.withMessage(
            Code.BAD_MODULE,
            "in tag at %s, mandatory attribute %s isn't being specified",
            tag.getLocation(),
            attr.getPublicName());
      }
      if (attrValues[i] == null) {
        attrValues[i] = Attribute.valueToStarlark(attr.getDefaultValueUnchecked());
      }
    }
    return new TypeCheckedTag(
        tagClass, attrValues, tag.isDevDependency(), tag.getLocation(), tag.getTagName());
  }

  /**
   * Whether the tag was specified on an extension proxy created with <code>dev_dependency=True
   * </code>.
   */
  public boolean isDevDependency() {
    return devDependency;
  }

  @Override
  public boolean isImmutable() {
    return true;
  }

  @Nullable
  @Override
  public Object getValue(String name) throws EvalException {
    Integer attrIndex = tagClass.getAttributeIndices().get(name);
    if (attrIndex == null) {
      return null;
    }
    return attrValues[attrIndex];
  }

  @Override
  public ImmutableCollection<String> getFieldNames() {
    return tagClass.getAttributeIndices().keySet();
  }

  @Nullable
  @Override
  public String getErrorMessageForUnknownField(String field) {
    return "unknown attribute " + field;
  }

  @Override
  public void debugPrint(Printer printer, StarlarkSemantics semantics) {
    printer.append(String.format("'%s' tag at %s", tagClassName, location));
  }
}
