package com.indie.studio.domain.stage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.indie.studio.domain.stage.dto.StageJobView;
import com.indie.studio.domain.stage.entity.StageJobEntity;
import com.indie.studio.domain.stage.entity.StageJobEntity.Status;
import com.indie.studio.domain.stage.entity.StageJobImageEntity;
import com.indie.studio.domain.stage.entity.StageJobImageEntity.Kind;
import com.indie.studio.domain.stage.exception.StageErrorCode;
import com.indie.studio.domain.stage.repository.StageJobImageRepository;
import com.indie.studio.domain.stage.repository.StageJobRepository;
import com.indie.studio.global.auth.AdminPrincipal;
import com.indie.studio.global.client.ServiceClient;
import com.indie.studio.global.client.ServiceClient.Target;
import com.indie.studio.global.common.exception.GeneralException;
import com.indie.studio.global.config.StudioProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 도면 이미지와 값을 저장하고 백그라운드에서 GPU 검증을 요청합니다.
 * 관리자가 결과를 확인한 뒤에만 Concert에 배치를 전달합니다. 작업 이미지는 Studio DB에 보관합니다.
 */
@Service
public class StageJobService {

    private static final Logger log = LoggerFactory.getLogger(StageJobService.class);

    private final StageJobRepository jobs;
    private final StageJobImageRepository images;
    private final GpuClient gpuClient;
    private final ServiceClient serviceClient;
    private final ExecutorService gpuExecutor;
    private final ObjectMapper objectMapper;
    private final StudioProperties.Gpu gpuProps;
    private final Clock clock;

    public StageJobService(StageJobRepository jobs, StageJobImageRepository images, GpuClient gpuClient,
                           ServiceClient serviceClient, ExecutorService gpuExecutor, ObjectMapper objectMapper,
                           StudioProperties props, Clock clock) {
        this.jobs = jobs;
        this.images = images;
        this.gpuClient = gpuClient;
        this.serviceClient = serviceClient;
        this.gpuExecutor = gpuExecutor;
        this.objectMapper = objectMapper;
        this.gpuProps = props.gpu();
        this.clock = clock;
    }

    /**
     * 무대 작업 정보를 등록합니다.
     *
     * @param venueId 공연장 ID
     * @param image 도면 이미지
     * @param drawing 관리자가 그린 도면 JSON
     * @param admin 요청한 관리자와 권한 정보
     */
    public StageJobView create(long venueId, byte[] image, String drawing, AdminPrincipal admin) {
        String contentType = image.length > gpuProps.maxImageBytes() ? null : ImageType.detect(image);
        if (contentType == null) throw new GeneralException(StageErrorCode.STAGE_IMAGE_INVALID);
        String drawingJson = canonicalJson(drawing);
        // 공연장이 있는지 Concert 에 확인하고 이름을 받아 둡니다 (없으면 Concert 의 404 가 그대로 나갑니다)
        JsonNode venue = serviceClient.get(Target.CONCERT, "/api/concerts/venues/" + venueId, null, admin);
        String venueName = venue == null ? "" : venue.path("name").asText("");

        StageJobEntity job = jobs.save(new StageJobEntity(venueId, venueName, drawingJson, admin.id(), clock.instant()));
        images.save(new StageJobImageEntity(job.getId(), Kind.ORIGINAL, contentType, image));
        long jobId = job.getId();
        gpuExecutor.submit(() -> analyze(jobId));
        return view(job, true);
    }

    /** 백그라운드 - GPU 서버가 느려도 요청 스레드와 DB 커넥션을 붙잡지 않습니다 */
    void analyze(long jobId) {
        StageJobEntity job = jobs.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != Status.PENDING) return;
        try {
            job.analyzing();
            jobs.save(job);
            StageJobImageEntity original = images.findByJobIdAndKind(jobId, Kind.ORIGINAL).orElseThrow();
            GpuClient.Result result = gpuClient.verify(job.getVenueId(), job.getVenueName(), original.getData(),
                    original.getContentType(), job.getDrawingJson());
            if (result.image() != null) {
                String type = ImageType.detect(result.image());
                if (type != null) images.save(new StageJobImageEntity(jobId, Kind.RESULT, type, result.image()));
                else log.warn("gpu_result_image_ignored jobId={} reason=unknown_format", jobId);
            }
            job.reviewReady(objectMapper.writeValueAsString(result.values()), clock.instant());
            jobs.save(job);
            log.info("stage_job_review_ready jobId={} venueId={}", jobId, job.getVenueId());
        } catch (Exception e) {
            log.warn("stage_job_failed jobId={} error={}", jobId, e.toString());
            job.failed("GPU 검증에 실패했습니다: " + e.getMessage(), clock.instant());
            jobs.save(job);
        }
    }

    /** 서버가 꺼졌다 켜지면 돌던 검증은 사라집니다 - 멈춘 작업을 FAILED 로 닫아 화면이 계속 기다리지 않게 합니다 */
    @EventListener(ApplicationReadyEvent.class)
    public void closeInterrupted() {
        for (StageJobEntity job : jobs.findByStatusIn(List.of(Status.PENDING, Status.ANALYZING))) {
            job.failed("서버가 다시 시작되어 검증이 중단됐습니다. 다시 요청해 주세요", clock.instant());
            jobs.save(job);
        }
    }

    /**
     * 무대 작업 상세를 조회합니다.
     *
     * @param jobId 도면 검증 작업 ID
     */
    public StageJobView get(long jobId) {
        return view(find(jobId), true);
    }

    /**
     * 무대 작업 목록을 조회합니다.
     *
     * @param venueId 공연장 ID
     */
    public List<StageJobView> list(long venueId) {
        return jobs.findTop50ByVenueIdOrderByIdDesc(venueId).stream().map(j -> view(j, false)).toList();
    }

    /**
     * 무대 작업 작업 이미지를 조회합니다.
     *
     * @param jobId 도면 검증 작업 ID
     * @param kind 조회할 작업 이미지 종류
     */
    public StageJobImageEntity image(long jobId, Kind kind) {
        find(jobId);
        return images.findByJobIdAndKind(jobId, kind).orElseThrow(() -> new GeneralException(StageErrorCode.STAGE_IMAGE_NOT_FOUND));
    }

    /**
     * 관리자가 확인한 값을 Concert 로 보냅니다. edited 가 있으면 그 값(관리자가 고친 것)을, 없으면 GPU 가 돌려준 값을 그대로.
     * 값은 구역 하나(객체), 구역 배열, { sections: [...] } 모두 받습니다. 구역마다 PUT 한 번입니다.
     * 중간에 실패하면 작업은 REVIEW 로 남고, 다시 보내면 Concert 가 같은 구역을 덮어씁니다.
     *
     * @param jobId 도면 검증 작업 ID
     * @param edited 관리자가 검수하거나 수정한 값
     * @param admin 요청한 관리자와 권한 정보
     */
    public JsonNode confirm(long jobId, JsonNode edited, AdminPrincipal admin) {
        StageJobEntity job = find(jobId);
        if (job.getStatus() != Status.REVIEW) throw new GeneralException(StageErrorCode.STAGE_JOB_NOT_REVIEWABLE);
        JsonNode values = edited != null && !edited.isNull() ? edited : parse(job.getResultJson());
        ArrayNode saved = sendSections(job.getVenueId(), values, admin);
        job.sent(values.toString(), admin.id(), clock.instant());
        jobs.save(job);
        log.info("stage_job_sent jobId={} venueId={} sections={} adminId={}", jobId, job.getVenueId(), saved.size(), admin.id());
        return saved;
    }

    /**
     * GPU 검증 없이 관리자가 넣은 구역 값을 바로 Concert 로 보냅니다 . 작업(stage_job)은 만들지 않습니다
     *
     * @param venueId 공연장 ID
     * @param values Concert에 저장할 구역 배치 값
     * @param admin 요청한 관리자와 권한 정보
     */
    public JsonNode saveSections(long venueId, JsonNode values, AdminPrincipal admin) {
        ArrayNode saved = sendSections(venueId, values, admin);
        log.info("stage_sections_saved venueId={} sections={} adminId={}", venueId, saved.size(), admin.id());
        return saved;
    }

    /** 구역마다 PUT 한 번 - 중간에 실패하면 Concert 의 오류가 그대로 나가고, 다시 보내면 Concert 가 같은 구역을 덮어씁니다 */
    private ArrayNode sendSections(long venueId, JsonNode values, AdminPrincipal admin) {
        List<JsonNode> sections = sections(values);
        if (sections.isEmpty()) throw new GeneralException(StageErrorCode.STAGE_VALUES_INVALID);
        ArrayNode saved = objectMapper.createArrayNode();
        String path = "/internal/admin/concerts/venues/" + venueId + "/sections";
        for (JsonNode section : sections) {
            saved.add(serviceClient.send(Target.CONCERT, HttpMethod.PUT, path, section, admin));
        }
        return saved;
    }

    private static List<JsonNode> sections(JsonNode values) {
        JsonNode list = values.isObject() && values.path("sections").isArray() ? values.get("sections") : values;
        List<JsonNode> out = new ArrayList<>();
        if (list.isArray()) list.forEach(out::add);
        else if (list.isObject()) out.add(list);
        return out;
    }

    private StageJobEntity find(long jobId) {
        return jobs.findById(jobId).orElseThrow(() -> new GeneralException(StageErrorCode.STAGE_JOB_NOT_FOUND));
    }

    private StageJobView view(StageJobEntity j, boolean detail) {
        return new StageJobView(j.getId(), j.getVenueId(), j.getVenueName(), j.getStatus(), j.getErrorMessage(),
                j.getRequestedBy(), j.getSentBy(), j.getCreatedAt(), j.getAnalyzedAt(), j.getSentAt(),
                detail ? images.existsByJobIdAndKind(j.getId(), Kind.RESULT) : null,
                detail ? parse(j.getDrawingJson()) : null,
                detail ? parse(j.getResultJson()) : null,
                detail ? parse(j.getSentJson()) : null);
    }

    private String canonicalJson(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node == null || !node.isContainerNode()) throw new GeneralException(StageErrorCode.STAGE_DRAWING_INVALID);
            return node.toString();
        } catch (JsonProcessingException e) {
            throw new GeneralException(StageErrorCode.STAGE_DRAWING_INVALID);
        }
    }

    private JsonNode parse(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
