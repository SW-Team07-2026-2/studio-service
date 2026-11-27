package com.indie.studio.domain.product.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.indie.studio.domain.image.ImageGuard;
import com.indie.studio.global.auth.AdminPrincipal;
import com.indie.studio.global.auth.Permission;
import com.indie.studio.global.auth.RequirePermission;
import com.indie.studio.global.client.ServiceClient;
import com.indie.studio.global.client.ServiceClient.Target;
import com.indie.studio.global.common.response.ApiResponse;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpMethod;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 상품 관리자 화면 -> Studio -> Catalog 상품, 아티스트, 앨범과 재고 API. 본문 검증은 Catalog가 수행합니다. */
@RestController
public class ProductAdminController {

    private final ServiceClient serviceClient;
    private final ImageGuard images;

    public ProductAdminController(ServiceClient serviceClient, ImageGuard images) {
        this.serviceClient = serviceClient;
        this.images = images;
    }

    /**
     * Catalog에서 굿즈를 조회합니다.
     *
     * @param query 대상 서비스에 전달할 조회 조건
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/products/goods")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<JsonNode> goods(@RequestParam MultiValueMap<String, String> query,
                                       @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CATALOG, "/api/catalog/goods", query, admin));
    }

    /**
     * Catalog에서 피지컬 앨범을 조회합니다.
     *
     * @param query 대상 서비스에 전달할 조회 조건
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/products/physical-albums")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<JsonNode> physicalAlbums(@RequestParam MultiValueMap<String, String> query,
                                                @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CATALOG, "/api/catalog/physical-albums", query, admin));
    }

    /**
     * Catalog에서 상품 상세를 조회합니다.
     *
     * @param productId 상품 ID
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/products/{productId}")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<JsonNode> get(@PathVariable @Positive long productId,
                                     @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CATALOG, "/api/catalog/products/" + productId, null, admin));
    }

    /**
     * Catalog에 상품 등록을 요청합니다.
     *
     * @param body 대상 서비스에 전달할 요청 본문
     * @param admin 요청한 관리자와 권한 정보
     */
    @PostMapping("/api/studio/products")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<JsonNode> create(@RequestBody JsonNode body,
                                        @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        images.check(body, "thumbnailUrl");
        return ApiResponse.onSuccess(serviceClient.send(Target.CATALOG, HttpMethod.POST, "/internal/admin/catalog/products", body, admin));
    }

    /**
     * Catalog에 상품 수정을 요청합니다.
     *
     * @param productId 상품 ID
     * @param body 대상 서비스에 전달할 요청 본문
     * @param admin 요청한 관리자와 권한 정보
     */
    @PatchMapping("/api/studio/products/{productId}")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<JsonNode> update(@PathVariable @Positive long productId, @RequestBody JsonNode body,
                                        @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        images.check(body, "thumbnailUrl");
        return ApiResponse.onSuccess(serviceClient.send(Target.CATALOG, HttpMethod.PATCH, "/internal/admin/catalog/products/" + productId, body, admin));
    }

    /**
     * Catalog에 아티스트 등록을 요청합니다.
     *
     * @param body 대상 서비스에 전달할 요청 본문
     * @param admin 요청한 관리자와 권한 정보
     */
    @PostMapping("/api/studio/products/artists")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<JsonNode> createArtist(@RequestBody JsonNode body,
                                              @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        images.check(body, "profileUrl");
        return ApiResponse.onSuccess(serviceClient.send(Target.CATALOG, HttpMethod.POST, "/internal/admin/catalog/artists", body, admin));
    }

    /**
     * Catalog에 아티스트 수정을 요청합니다.
     *
     * @param artistId 아티스트 ID
     * @param body 대상 서비스에 전달할 요청 본문
     * @param admin 요청한 관리자와 권한 정보
     */
    @PatchMapping("/api/studio/products/artists/{artistId}")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<JsonNode> updateArtist(@PathVariable @Positive long artistId, @RequestBody JsonNode body,
                                              @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        images.check(body, "profileUrl");
        return ApiResponse.onSuccess(serviceClient.send(Target.CATALOG, HttpMethod.PATCH, "/internal/admin/catalog/artists/" + artistId, body, admin));
    }

    /**
     * Catalog에서 앨범 상세를 조회합니다.
     *
     * @param albumId 앨범 ID
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/products/albums/{albumId}")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<JsonNode> album(@PathVariable @Positive long albumId,
                                       @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CATALOG, "/api/catalog/albums/" + albumId, null, admin));
    }

    /**
     * Catalog에 앨범 수정을 요청합니다.
     *
     * @param albumId 앨범 ID
     * @param body 대상 서비스에 전달할 요청 본문
     * @param admin 요청한 관리자와 권한 정보
     */
    @PatchMapping("/api/studio/products/albums/{albumId}")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<JsonNode> updateAlbum(@PathVariable @Positive long albumId, @RequestBody JsonNode body,
                                             @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        images.check(body, "thumbnailUrl");
        return ApiResponse.onSuccess(serviceClient.send(Target.CATALOG, HttpMethod.PATCH, "/internal/admin/catalog/albums/" + albumId, body, admin));
    }

    /**
     * Catalog에 상품 옵션 수정을 요청합니다.
     *
     * @param productId 상품 ID
     * @param body 대상 서비스에 전달할 요청 본문
     * @param admin 요청한 관리자와 권한 정보
     */
    @PutMapping("/api/studio/products/{productId}/options")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<JsonNode> options(@PathVariable @Positive long productId, @RequestBody JsonNode body,
                                         @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CATALOG, HttpMethod.PUT, "/internal/admin/catalog/products/" + productId + "/options", body, admin));
    }

    /**
     * Catalog에 옵션 재고 증감을 요청합니다.
     *
     * @param optionId 상품 옵션 ID
     * @param body 대상 서비스에 전달할 요청 본문
     * @param admin 요청한 관리자와 권한 정보
     */
    @PostMapping("/api/studio/products/options/{optionId}/stock")
    @RequirePermission(Permission.STOCK)
    public ApiResponse<JsonNode> adjustStock(@PathVariable @Positive long optionId, @RequestBody JsonNode body,
                                             @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CATALOG, HttpMethod.POST, "/internal/admin/catalog/options/" + optionId + "/stock", body, admin));
    }

    /**
     * Catalog에 옵션 재고 설정을 요청합니다.
     *
     * @param optionId 상품 옵션 ID
     * @param body 대상 서비스에 전달할 요청 본문
     * @param admin 요청한 관리자와 권한 정보
     */
    @PutMapping("/api/studio/products/options/{optionId}/stock")
    @RequirePermission(Permission.STOCK)
    public ApiResponse<JsonNode> setStock(@PathVariable @Positive long optionId, @RequestBody JsonNode body,
                                          @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CATALOG, HttpMethod.PUT, "/internal/admin/catalog/options/" + optionId + "/stock", body, admin));
    }

    /**
     * Catalog에서 선택할 아티스트 목록을 조회합니다.
     *
     * @param query 대상 서비스에 전달할 조회 조건
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/products/options/artists")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<JsonNode> artistOptions(@RequestParam MultiValueMap<String, String> query,
                                               @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CATALOG, "/api/catalog/artists", query, admin));
    }

    /**
     * Catalog에서 선택한 아티스트 상세를 조회합니다.
     *
     * @param artistId 아티스트 ID
     * @param admin 요청한 관리자와 권한 정보
     */
    @GetMapping("/api/studio/products/options/artists/{artistId}")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<JsonNode> artistOption(@PathVariable @Positive long artistId,
                                              @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CATALOG, "/api/catalog/artists/" + artistId, null, admin));
    }
}
