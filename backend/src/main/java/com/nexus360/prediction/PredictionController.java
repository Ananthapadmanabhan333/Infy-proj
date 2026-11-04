package com.nexus360.prediction;

import com.nexus360.admin.SettingsService;
import com.nexus360.common.PageResponse;
import com.nexus360.prediction.PredictionDtos.CapacityForecast;
import com.nexus360.prediction.PredictionDtos.ClassificationPrediction;
import com.nexus360.prediction.PredictionDtos.EvaluateRequest;
import com.nexus360.prediction.PredictionDtos.IncidentPredictions;
import com.nexus360.prediction.PredictionDtos.JobDto;
import com.nexus360.prediction.PredictionDtos.ModelDto;
import com.nexus360.prediction.PredictionDtos.ModelStatus;
import com.nexus360.prediction.PredictionDtos.ModelType;
import com.nexus360.prediction.PredictionDtos.Performance;
import com.nexus360.prediction.PredictionDtos.PredictionRecord;
import com.nexus360.prediction.PredictionDtos.ReconcileResult;
import com.nexus360.prediction.PredictionDtos.ServiceHealthForecast;
import com.nexus360.prediction.PredictionDtos.TrainRequest;
import com.nexus360.security.CurrentActor;
import com.nexus360.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SA Predictive Intelligence (docs/RELEASE4_SPEC.md §4.3). Reads: {@code OBSERVABILITY_READERS} (incident predictions additionally
 * require access to the incident); training, evaluation, activation, retirement and outcome reconciliation: {@code PREDICTION_ADMINS}.
 */
@Tag(name = "Predictions")
@Validated
@RestController
@RequestMapping("/api/predictions")
@RequiredArgsConstructor
public class PredictionController {

    private final PredictionModelService models;
    private final PredictionService predictions;
    private final PredictionOutcomeService outcomes;
    private final SettingsService settings;

    @Operation(summary = "Model types, algorithms and the minimum sample sizes")
    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/meta")
    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, String> methods = new LinkedHashMap<>();
        Arrays.stream(ModelType.values()).forEach(t -> methods.put(t.name(), PredictionModelService.method(t)));
        m.put("types", methods);
        m.put("statuses", Arrays.stream(ModelStatus.values()).map(Enum::name).toList());
        m.put("minSamples", settings.getInt("predictions.min_samples", 200));
        m.put("minPositives", settings.getInt("predictions.min_positives", 30));
        m.put("minForecastPoints", settings.getInt("predictions.min_forecast_points", 100));
        m.put("slaFeatures", PredictionDatasets.SLA_FEATURES);
        m.put("recurrenceFeatures", PredictionDatasets.RECURRENCE_FEATURES);
        m.put("recurrenceDays", PredictionDatasets.RECURRENCE_DAYS);
        m.put("capacityMetricPattern", PredictionDatasets.CAPACITY_PATTERN);
        return m;
    }

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/models")
    public List<ModelDto> list(@RequestParam(required = false) ModelType type, @RequestParam(required = false) ModelStatus status) {
        return models.list(type, status);
    }

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/models/{id}")
    public ModelDto get(@PathVariable Long id) {
        return models.get(id);
    }

    @Operation(summary = "Train a new model version (time-based split, offline evaluation); never activates it")
    @PreAuthorize(Roles.PREDICTION_ADMINS)
    @PostMapping("/models/train")
    @ResponseStatus(HttpStatus.CREATED)
    public ModelDto train(@Valid @RequestBody TrainRequest request) {
        return models.train(request, CurrentActor.require(), "MANUAL");
    }

    @Operation(summary = "Out-of-time evaluation of a stored model on a new window (no refit)")
    @PreAuthorize(Roles.PREDICTION_ADMINS)
    @PostMapping("/models/{id}/evaluate")
    public JobDto evaluate(@PathVariable Long id, @Valid @RequestBody(required = false) EvaluateRequest request) {
        return models.evaluate(id, request, CurrentActor.require());
    }

    @Operation(summary = "Activate a model version; the previously ACTIVE version of the type is retired")
    @PreAuthorize(Roles.PREDICTION_ADMINS)
    @PostMapping("/models/{id}/activate")
    public ModelDto activate(@PathVariable Long id) {
        return models.activate(id, CurrentActor.require());
    }

    @PreAuthorize(Roles.PREDICTION_ADMINS)
    @PostMapping("/models/{id}/retire")
    public ModelDto retire(@PathVariable Long id) {
        return models.retire(id, CurrentActor.require());
    }

    @Operation(summary = "Live accuracy from recorded outcomes compared with the offline evaluation, with drift warning")
    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/models/{id}/performance")
    public Performance performance(@PathVariable Long id, @RequestParam(required = false) Integer days) {
        return outcomes.performance(id, days);
    }

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/jobs")
    public List<JobDto> jobs(@RequestParam(required = false) Long modelId, @RequestParam(required = false) Integer limit) {
        return models.jobs(modelId, limit);
    }

    @Operation(summary = "SLA breach and recurrence predictions for an incident (recorded)")
    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/incidents/{id}")
    public IncidentPredictions incident(@PathVariable Long id) {
        return predictions.incident(id, CurrentActor.require(), true);
    }

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/incidents/{id}/sla-breach")
    public ClassificationPrediction slaBreach(@PathVariable Long id) {
        return predictions.slaBreach(id, CurrentActor.require(), true);
    }

    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/incidents/{id}/recurrence")
    public ClassificationPrediction recurrence(@PathVariable Long id) {
        return predictions.recurrence(id, CurrentActor.require(), true);
    }

    @Operation(summary = "Service health forecast: daily incident and alert counts (and error rate when spans exist) with 95% intervals")
    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/services/{id}/health")
    public ServiceHealthForecast serviceHealth(@PathVariable Long id, @RequestParam(required = false) Integer horizonDays) {
        return predictions.serviceHealth(id, horizonDays, CurrentActor.require(), true);
    }

    @Operation(summary = "Capacity risk of utilisation series of a CI, a service or one series: time to threshold with a 95% interval")
    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/capacity")
    public CapacityForecast capacity(@RequestParam(required = false) Long seriesId, @RequestParam(required = false) Long ciId,
                                     @RequestParam(required = false) Long serviceId, @RequestParam(required = false) String metric,
                                     @RequestParam(required = false) Double threshold, @RequestParam(required = false) Integer horizonHours) {
        return predictions.capacity(seriesId, ciId, serviceId, metric, threshold, horizonHours, CurrentActor.require(), true);
    }

    @Operation(summary = "Stored (served) predictions with their recorded outcomes")
    @PreAuthorize(Roles.OBSERVABILITY_READERS)
    @GetMapping("/records")
    public PageResponse<PredictionRecord> records(@RequestParam(required = false) Long modelId, @RequestParam(required = false) String subjectType,
                                                  @RequestParam(required = false) Long subjectId, @RequestParam(required = false) String outcome,
                                                  @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return predictions.list(modelId, subjectType, subjectId, outcome, page, size);
    }

    @Operation(summary = "Record known outcomes now and re-check drift of the ACTIVE models (the scheduler does this every 15 minutes)")
    @PreAuthorize(Roles.PREDICTION_ADMINS)
    @PostMapping("/outcomes/reconcile")
    public ReconcileResult reconcile() {
        return outcomes.reconcile();
    }
}
