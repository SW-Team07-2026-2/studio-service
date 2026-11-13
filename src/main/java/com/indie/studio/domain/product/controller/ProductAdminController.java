package com.indie.studio.domain.product.controller;

import com.fasterxml.jackson.databind.JsonNode;
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

/**
 * 굿즈 관리자 — 굿즈와 피지컬 앨범(LP · CD · 카세트). 상품의 주인은 AWS 의 Catalog 입니다 (catalog v2: product → product_option → stock).
 * 조회는 Catalog 의 공개 조회를 그대로 쓰고, 등록 · 수정 · 재고는 Catalog 내부 관리자 API 로 보냅니다.
 */
@RestController
public class ProductAdminController {

    private final ServiceClient serviceClient;

    public ProductAdminController(ServiceClient serviceClient) {
        this.serviceClient = serviceClient;
    }

    /** ?artistId=&category=&page=&size= */
    @GetMapping("/api/studio/products/goods")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<JsonNode> goods(@RequestParam MultiValueMap<String, String> query,
                                       @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CATALOG, "/api/catalog/goods", query, admin));
    }

    /** ?format=LP|CD|CASSETTE&from=&to=&page=&size= */
    @GetMapping("/api/studio/products/physical-albums")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<JsonNode> physicalAlbums(@RequestParam MultiValueMap<String, String> query,
                                                @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CATALOG, "/api/catalog/physical-albums", query, admin));
    }

    /** 상품 + 옵션별 가격 · 재고 */
    @GetMapping("/api/studio/products/{id}")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<JsonNode> get(@PathVariable @Positive long id,
                                     @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CATALOG, "/api/catalog/products/" + id, null, admin));
    }

    /** 상품 등록 — productType(GOODS · PHYSICAL_ALBUM) + 종류별 값 + options[{ name, vinylColor, size, price, qty }] */
    @PostMapping("/api/studio/products")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<JsonNode> create(@RequestBody JsonNode body,
                                        @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CATALOG, HttpMethod.POST, "/internal/admin/catalog/products", body, admin));
    }

    /** 상품 수정 — 이름 · 썸네일 · 구매처 주소 · 분류 등 보낸 값만 */
    @PatchMapping("/api/studio/products/{id}")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<JsonNode> update(@PathVariable @Positive long id, @RequestBody JsonNode body,
                                        @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CATALOG, HttpMethod.PATCH, "/internal/admin/catalog/products/" + id, body, admin));
    }

    /** 옵션 추가 · 수정 — { options: [{ id?, name, vinylColor, size, price }] }. 새 옵션에는 재고 행이 함께 생깁니다 */
    @PutMapping("/api/studio/products/{id}/options")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<JsonNode> options(@PathVariable @Positive long id, @RequestBody JsonNode body,
                                         @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CATALOG, HttpMethod.PUT, "/internal/admin/catalog/products/" + id + "/options", body, admin));
    }

    /** 재고 조정 — { delta, reason }. 입고는 +, 회수 · 파손은 −. 0 아래로는 내려가지 않습니다 */
    @PostMapping("/api/studio/products/options/{optionId}/stock")
    @RequirePermission(Permission.STOCK)
    public ApiResponse<JsonNode> adjustStock(@PathVariable @Positive long optionId, @RequestBody JsonNode body,
                                             @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.send(Target.CATALOG, HttpMethod.POST, "/internal/admin/catalog/options/" + optionId + "/stock", body, admin));
    }

    /** 굿즈를 걸 아티스트 찾기 — ?q=&genre=&page=&size= */
    @GetMapping("/api/studio/products/options/artists")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<JsonNode> artistOptions(@RequestParam MultiValueMap<String, String> query,
                                               @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CATALOG, "/api/catalog/artists", query, admin));
    }

    /** 그 아티스트의 앨범 목록 (피지컬 앨범을 걸 앨범 고르기) */
    @GetMapping("/api/studio/products/options/artists/{artistId}")
    @RequirePermission(Permission.PRODUCT)
    public ApiResponse<JsonNode> artistOption(@PathVariable @Positive long artistId,
                                              @RequestAttribute(AdminPrincipal.ATTRIBUTE) AdminPrincipal admin) {
        return ApiResponse.onSuccess(serviceClient.get(Target.CATALOG, "/api/catalog/artists/" + artistId, null, admin));
    }
}
