package com.linkedin.datahub.upgrade.system.entity.steps;

import static com.linkedin.metadata.Constants.*;

import com.google.common.collect.ImmutableList;
import com.linkedin.common.AuditStamp;
import com.linkedin.common.urn.Urn;
import com.linkedin.common.urn.UrnUtils;
import com.linkedin.data.DataMap;
import com.linkedin.datahub.upgrade.UpgradeContext;
import com.linkedin.datahub.upgrade.UpgradeStep;
import com.linkedin.datahub.upgrade.UpgradeStepResult;
import com.linkedin.datahub.upgrade.impl.DefaultUpgradeStepResult;
import com.linkedin.entity.EntityResponse;
import com.linkedin.events.metadata.ChangeType;
import com.linkedin.metadata.Constants;
import com.linkedin.metadata.boot.BootstrapStep;
import com.linkedin.metadata.entity.EntityService;
import com.linkedin.metadata.query.SearchFlags;
import com.linkedin.metadata.query.filter.Condition;
import com.linkedin.metadata.query.filter.ConjunctiveCriterion;
import com.linkedin.metadata.query.filter.ConjunctiveCriterionArray;
import com.linkedin.metadata.query.filter.Criterion;
import com.linkedin.metadata.query.filter.CriterionArray;
import com.linkedin.metadata.query.filter.Filter;
import com.linkedin.metadata.search.ScrollResult;
import com.linkedin.metadata.search.SearchEntity;
import com.linkedin.metadata.search.SearchService;
import com.linkedin.metadata.utils.GenericRecordUtils;
import com.linkedin.mxe.MetadataChangeProposal;
import com.linkedin.mxe.SystemMetadata;
import com.linkedin.policy.DataHubPolicyInfo;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * This bootstrap step is responsible for upgrading DataHub policy documents with new searchable
 * fields in ES
 */
@Slf4j
public class BackfillPolicyFieldsStep implements UpgradeStep {
  private static final String UPGRADE_ID = "BackfillPolicyFieldsStep";
  private static final Urn UPGRADE_ID_URN = BootstrapStep.getUpgradeUrn(UPGRADE_ID);
  private final boolean reprocessEnabled;
  private final Integer batchSize;
  private final EntityService<?> entityService;
  private final SearchService _searchService;

  public BackfillPolicyFieldsStep(
      EntityService<?> entityService,
      SearchService searchService,
      boolean reprocessEnabled,
      Integer batchSize) {
    this.entityService = entityService;
    this._searchService = searchService;
    this.reprocessEnabled = reprocessEnabled;
    this.batchSize = batchSize;
  }

  @Override
  public String id() {
    return UPGRADE_ID;
  }

  @Override
  public Function<UpgradeContext, UpgradeStepResult> executable() {
    return (context) -> {
      final AuditStamp auditStamp =
          new AuditStamp()
              .setActor(UrnUtils.getUrn(Constants.SYSTEM_ACTOR))
              .setTime(System.currentTimeMillis());

      String scrollId = null;
      int migratedCount = 0;
      do {
        log.info("Upgrading batch of policies {}-{}", migratedCount, migratedCount + batchSize);
        scrollId = backfillPolicies(auditStamp, scrollId);
        migratedCount += batchSize;
      } while (scrollId != null);

      BootstrapStep.setUpgradeResult(UPGRADE_ID_URN, entityService);

      return new DefaultUpgradeStepResult(id(), UpgradeStepResult.Result.SUCCEEDED);
    };
  }

  /**
   * Returns whether the upgrade should proceed if the step fails after exceeding the maximum
   * retries.
   */
  @Override
  public boolean isOptional() {
    return true;
  }

  /**
   * Returns whether the upgrade should be skipped. Uses previous run history or the environment
   * variables REPROCESS_DEFAULT_POLICY_FIELDS & BACKFILL_BROWSE_PATHS_V2 to determine whether to
   * skip.
   */
  @Override
  public boolean skip(UpgradeContext context) {

    if (reprocessEnabled) {
      return false;
    }

    boolean previouslyRun = entityService.exists(UPGRADE_ID_URN, true);
    if (previouslyRun) {
      log.info("{} was already run. Skipping.", id());
    }
    return previouslyRun;
  }

  private String backfillPolicies(AuditStamp auditStamp, String scrollId) {

    final Filter filter = backfillPolicyFieldFilter();
    final ScrollResult scrollResult =
        _searchService.scrollAcrossEntities(
            ImmutableList.of(Constants.POLICY_ENTITY_NAME),
            "*",
            filter,
            null,
            scrollId,
            null,
            batchSize,
            new SearchFlags()
                .setFulltext(true)
                .setSkipCache(true)
                .setSkipHighlighting(true)
                .setSkipAggregates(true));

    if (scrollResult.getNumEntities() == 0 || scrollResult.getEntities().isEmpty()) {
      return null;
    }

    for (SearchEntity searchEntity : scrollResult.getEntities()) {
      try {
        ingestPolicyFields(searchEntity.getEntity(), auditStamp);
      } catch (Exception e) {
        // don't stop the whole step because of one bad urn or one bad ingestion
        log.error(
            String.format(
                "Error ingesting default browsePathsV2 aspect for urn %s",
                searchEntity.getEntity()),
            e);
      }
    }

    return scrollResult.getScrollId();
  }

  private Filter backfillPolicyFieldFilter() {
    // Condition: Does not have at least 1 of: `privileges`, `editable`, `state` or `type`
    ConjunctiveCriterionArray conjunctiveCriterionArray = new ConjunctiveCriterionArray();

    conjunctiveCriterionArray.add(getCriterionForMissingField("privilege"));
    conjunctiveCriterionArray.add(getCriterionForMissingField("editable"));
    conjunctiveCriterionArray.add(getCriterionForMissingField("state"));
    conjunctiveCriterionArray.add(getCriterionForMissingField("type"));

    Filter filter = new Filter();
    filter.setOr(conjunctiveCriterionArray);
    return filter;
  }

  private void ingestPolicyFields(Urn urn, AuditStamp auditStamp) {
    EntityResponse entityResponse = null;
    try {
      entityResponse =
          entityService.getEntityV2(
              urn.getEntityType(), urn, Collections.singleton(DATAHUB_POLICY_INFO_ASPECT_NAME));
    } catch (URISyntaxException e) {
      log.error(
          String.format(
              "Error getting DataHub Policy Info for entity with urn %s while restating policy information",
              urn),
          e);
    }

    if (entityResponse != null
        && entityResponse.getAspects().containsKey(DATAHUB_POLICY_INFO_ASPECT_NAME)) {
      final DataMap dataMap =
          entityResponse.getAspects().get(DATAHUB_POLICY_INFO_ASPECT_NAME).getValue().data();
      final DataHubPolicyInfo infoAspect = new DataHubPolicyInfo(dataMap);
      log.debug("Restating policy information for urn {} with value {}", urn, infoAspect);
      MetadataChangeProposal proposal = new MetadataChangeProposal();
      proposal.setEntityUrn(urn);
      proposal.setEntityType(urn.getEntityType());
      proposal.setAspectName(DATAHUB_POLICY_INFO_ASPECT_NAME);
      proposal.setChangeType(ChangeType.RESTATE);
      proposal.setSystemMetadata(
          new SystemMetadata()
              .setRunId(DEFAULT_RUN_ID)
              .setLastObserved(System.currentTimeMillis()));
      proposal.setAspect(GenericRecordUtils.serializeAspect(infoAspect));
      entityService.ingestProposal(proposal, auditStamp, true);
    }
  }

  @NotNull
  private static ConjunctiveCriterion getCriterionForMissingField(String field) {
    final Criterion missingPrivilegesField = new Criterion();
    missingPrivilegesField.setCondition(Condition.IS_NULL);
    missingPrivilegesField.setField(field);

    final CriterionArray criterionArray = new CriterionArray();
    criterionArray.add(missingPrivilegesField);
    final ConjunctiveCriterion conjunctiveCriterion = new ConjunctiveCriterion();
    conjunctiveCriterion.setAnd(criterionArray);
    return conjunctiveCriterion;
  }
}
