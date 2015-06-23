/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.restapi;

import com.streamsets.pipeline.api.impl.Utils;
import com.streamsets.pipeline.config.DataRuleDefinition;
import com.streamsets.pipeline.config.MetricElement;
import com.streamsets.pipeline.config.MetricType;
import com.streamsets.pipeline.config.MetricsRuleDefinition;
import com.streamsets.pipeline.config.PipelineConfiguration;
import com.streamsets.pipeline.config.RuleDefinitions;
import com.streamsets.pipeline.main.RuntimeInfo;
import com.streamsets.pipeline.restapi.bean.BeanHelper;
import com.streamsets.pipeline.restapi.bean.PipelineConfigurationJson;
import com.streamsets.pipeline.restapi.bean.RuleDefinitionsJson;
import com.streamsets.pipeline.stagelibrary.StageLibraryTask;
import com.streamsets.pipeline.store.PipelineStoreException;
import com.streamsets.pipeline.store.PipelineStoreTask;
import com.streamsets.pipeline.util.AuthzRole;
import com.streamsets.pipeline.validation.PipelineConfigurationValidator;
import com.streamsets.pipeline.validation.RuleDefinitionValidator;

import javax.annotation.security.DenyAll;
import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/v1/pipeline-library")
@DenyAll
public class PipelineStoreResource {
  private static final String HIGH_BAD_RECORDS_ID = "badRecordsAlertID";
  private static final String HIGH_BAD_RECORDS_TEXT = "High incidence of Bad Records";
  private static final String HIGH_BAD_RECORDS_METRIC_ID = "pipeline.batchErrorRecords.meter";
  private static final String HIGH_BAD_RECORDS_CONDITION = "${value() > 100}";

  private static final String HIGH_STAGE_ERRORS_ID = "stageErrorAlertID";
  private static final String HIGH_STAGE_ERRORS_TEXT = "High incidence of Error Messages";
  private static final String HIGH_STAGE_ERRORS_METRIC_ID = "pipeline.batchErrorMessages.meter";
  private static final String HIGH_STAGE_ERRORS_CONDITION = "${value() > 100}";

  private static final String PIPELINE_IDLE_ID = "idleGaugeID";
  private static final String PIPELINE_IDLE_TEXT = "Pipeline is Idle";
  private static final String PIPELINE_IDLE_METRIC_ID = "RuntimeStatsGauge.gauge";
  private static final String PIPELINE_IDLE_CONDITION = "${time:now() - value() > 120000}";

  private static final String BATCH_TIME_ID = "batchTimeAlertID";
  private static final String BATCH_TIME_TEXT = "Batch taking more time to process";
  private static final String BATCH_TIME_METRIC_ID = "RuntimeStatsGauge.gauge";
  private static final String BATCH_TIME_CONDITION = "${value() > 200}";

  private static final String MEMORY_LIMIt_ID = "memoryLimitAlertID";
  private static final String MEMORY_LIMIt_TEXT = "Memory limit for pipeline exceeded";
  private static final String MEMORY_LIMIt_METRIC_ID = "pipeline.memoryConsumed.counter";
  private static final String MEMORY_LIMIt_CONDITION = "${value() > (jvm:maxMemoryMB() * 0.65)}";


  private final RuntimeInfo runtimeInfo;
  private final PipelineStoreTask store;
  private final StageLibraryTask stageLibrary;
  private final URI uri;
  private final String user;

  @Inject
  public PipelineStoreResource(URI uri, Principal user, StageLibraryTask stageLibrary, PipelineStoreTask store,
                               RuntimeInfo runtimeInfo) {
    this.uri = uri;
    this.user = user.getName();
    this.stageLibrary = stageLibrary;
    this.store = store;
    this.runtimeInfo = runtimeInfo;
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @PermitAll
  public Response getPipelines() throws PipelineStoreException {
    return Response.ok().type(MediaType.APPLICATION_JSON).entity(BeanHelper.wrapPipelineInfo(store.getPipelines()))
      .build();
  }

  @Path("/{name}")
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @PermitAll
  public Response getInfo(
      @PathParam("name") String name,
      @QueryParam("rev") @DefaultValue("0") String rev,
      @QueryParam("get") @DefaultValue("pipeline") String get,
      @QueryParam("attachment") @DefaultValue("false") Boolean attachment)
      throws PipelineStoreException, URISyntaxException {
    Object data;
    if (get.equals("pipeline")) {
      PipelineConfiguration pipeline = store.load(name, rev);
      PipelineConfigurationValidator validator = new PipelineConfigurationValidator(stageLibrary, name, pipeline);
      validator.validate();
      pipeline.setValidation(validator);
      data = BeanHelper.wrapPipelineConfiguration(pipeline);
    } else if (get.equals("info")) {
      data = BeanHelper.wrapPipelineInfo(store.getInfo(name));
    } else if (get.equals("history")) {
      data = BeanHelper.wrapPipelineRevInfo(store.getHistory(name));
    } else {
      throw new IllegalArgumentException(Utils.format("Invalid value for parameter 'get': {}", get));
    }

    if(attachment) {
      Map<String, Object> envelope = new HashMap<String, Object>();
      envelope.put("pipelineConfig", data);

      com.streamsets.pipeline.config.RuleDefinitions ruleDefinitions = store.retrieveRules(name, rev);
      envelope.put("pipelineRules", BeanHelper.wrapRuleDefinitions(ruleDefinitions));

      return Response.ok().
        header("Content-Disposition", "attachment; filename=" + name + ".json").
        type(MediaType.APPLICATION_JSON).entity(envelope).build();
    } else
      return Response.ok().type(MediaType.APPLICATION_JSON).entity(data).build();

  }

  @Path("/{name}")
  @PUT
  @Produces(MediaType.APPLICATION_JSON)
  @RolesAllowed({ AuthzRole.CREATOR, AuthzRole.ADMIN })
  public Response create(
      @PathParam("name") String name,
      @QueryParam("description") @DefaultValue("") String description)
      throws PipelineStoreException, URISyntaxException {
    Utils.checkState(runtimeInfo.getExecutionMode() != RuntimeInfo.ExecutionMode.SLAVE,
      "This operation is not supported in SLAVE mode");
    PipelineConfiguration pipeline = store.create(name, description, user);

    //Add predefined Metric Rules to the pipeline
    List<MetricsRuleDefinition> metricsRuleDefinitions = new ArrayList<>();

    metricsRuleDefinitions.add(new MetricsRuleDefinition(HIGH_BAD_RECORDS_ID, HIGH_BAD_RECORDS_TEXT,
      HIGH_BAD_RECORDS_METRIC_ID, MetricType.METER, MetricElement.METER_COUNT, HIGH_BAD_RECORDS_CONDITION, false,
      false));

    metricsRuleDefinitions.add(new MetricsRuleDefinition(HIGH_STAGE_ERRORS_ID, HIGH_STAGE_ERRORS_TEXT,
      HIGH_STAGE_ERRORS_METRIC_ID, MetricType.METER, MetricElement.METER_COUNT, HIGH_STAGE_ERRORS_CONDITION, false,
      false));

    metricsRuleDefinitions.add(new MetricsRuleDefinition(PIPELINE_IDLE_ID, PIPELINE_IDLE_TEXT,
      PIPELINE_IDLE_METRIC_ID, MetricType.GAUGE, MetricElement.TIME_OF_LAST_RECEIVED_RECORD, PIPELINE_IDLE_CONDITION,
      false, false));

    metricsRuleDefinitions.add(new MetricsRuleDefinition(BATCH_TIME_ID, BATCH_TIME_TEXT, BATCH_TIME_METRIC_ID,
      MetricType.GAUGE, MetricElement.CURRENT_BATCH_AGE, BATCH_TIME_CONDITION, false, false));

    metricsRuleDefinitions.add(new MetricsRuleDefinition(MEMORY_LIMIt_ID, MEMORY_LIMIt_TEXT, MEMORY_LIMIt_METRIC_ID,
      MetricType.COUNTER, MetricElement.COUNTER_COUNT, MEMORY_LIMIt_CONDITION, false, false));

    RuleDefinitions ruleDefinitions = new RuleDefinitions(metricsRuleDefinitions,
      Collections.<DataRuleDefinition>emptyList(), Collections.<String>emptyList(), null);
    store.storeRules(name, "0", ruleDefinitions);

    PipelineConfigurationValidator validator = new PipelineConfigurationValidator(stageLibrary, name, pipeline);
    validator.validate();
    pipeline.setValidation(validator);
    return Response.created(UriBuilder.fromUri(uri).path(name).build()).entity(
      BeanHelper.wrapPipelineConfiguration(pipeline)).build();
  }

  @Path("/{name}")
  @DELETE
  @Produces(MediaType.APPLICATION_JSON)
  @RolesAllowed({ AuthzRole.CREATOR, AuthzRole.ADMIN })
  public Response delete(
      @PathParam("name") String name)
      throws PipelineStoreException, URISyntaxException {
    Utils.checkState(runtimeInfo.getExecutionMode() != RuntimeInfo.ExecutionMode.SLAVE,
      "This operation is not supported in SLAVE mode");
    store.delete(name);
    store.deleteRules(name);
    return Response.ok().build();
  }

  @Path("/{name}")
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @RolesAllowed({ AuthzRole.CREATOR, AuthzRole.ADMIN })
  public Response save(
      @PathParam("name") String name,
      @QueryParam("tag") @DefaultValue("0") String tag,
      @QueryParam("tagDescription") String tagDescription,
      PipelineConfigurationJson pipeline)
      throws PipelineStoreException, URISyntaxException {
    Utils.checkState(runtimeInfo.getExecutionMode() != RuntimeInfo.ExecutionMode.SLAVE,
      "This operation is not supported in SLAVE mode");

    PipelineConfiguration pipelineConfig = BeanHelper.unwrapPipelineConfiguration(
      pipeline);
    PipelineConfigurationValidator validator = new PipelineConfigurationValidator(stageLibrary, name, pipelineConfig);
    validator.validate();
    pipelineConfig.setValidation(validator);
    pipelineConfig = store.save(name, user, tag, tagDescription, pipelineConfig);
    return Response.ok().entity(BeanHelper.wrapPipelineConfiguration(pipelineConfig)).build();
  }

  @Path("/{name}/rules")
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @PermitAll
  public Response getRules(
    @PathParam("name") String name,
    @QueryParam("rev") @DefaultValue("0") String rev) throws PipelineStoreException {
    com.streamsets.pipeline.config.RuleDefinitions ruleDefinitions = store.retrieveRules(name, rev);
    if(ruleDefinitions != null) {
      RuleDefinitionValidator ruleDefinitionValidator = new RuleDefinitionValidator();
      ruleDefinitionValidator.validateRuleDefinition(ruleDefinitions);
    }
    return Response.ok().type(MediaType.APPLICATION_JSON).entity(
      BeanHelper.wrapRuleDefinitions(ruleDefinitions)).build();
  }

  @Path("/{name}/rules")
  @POST
  @Produces(MediaType.APPLICATION_JSON)
  @RolesAllowed({ AuthzRole.CREATOR, AuthzRole.MANAGER, AuthzRole.ADMIN })
  public Response saveRules(
    @PathParam("name") String name,
    @QueryParam("rev") @DefaultValue("0") String rev,
    RuleDefinitionsJson ruleDefinitionsJson) throws PipelineStoreException {
    com.streamsets.pipeline.config.RuleDefinitions ruleDefs = BeanHelper.unwrapRuleDefinitions(ruleDefinitionsJson);
    RuleDefinitionValidator ruleDefinitionValidator = new RuleDefinitionValidator();
    ruleDefinitionValidator.validateRuleDefinition(ruleDefs);
    ruleDefs = store.storeRules(name, rev, ruleDefs);
    return Response.ok().type(MediaType.APPLICATION_JSON).entity(BeanHelper.wrapRuleDefinitions(ruleDefs)).build();
  }

}
