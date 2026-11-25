package com.example.loopcut.exports;

import com.example.loopcut.common.ApiResponse;
import com.example.loopcut.common.AuthInterceptor;
import com.example.loopcut.exports.ExportService.FileData;
import com.example.loopcut.exports.ExportService.Preview;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "데이터 내보내기", description = "구독·결제 내역을 CSV / 엑셀 파일로 내려받기")
@RestController
@RequestMapping("/exports")
public class ExportController {

    private static final String PARAMS_NOTE = """
            - `items`: `subscriptions`(현재 구독), `history`(지난 구독 이력), `payments`(결제 내역) 중 1개 이상, 쉼표로 구분
            - `format`: `csv`(기본) 또는 `xlsx`. `pdf`는 아직 지원하지 않아요 (`400 FORMAT_NOT_SUPPORTED`)
            - `period`: `all`(기본) / `1y`(최근 1년) / `6m`(최근 6개월) / `custom`. **결제 내역(`payments`)에만** 적용돼요.
            - `from`, `to`: `period=custom`일 때 필수 (`YYYY-MM-DD`, 양 끝 날짜 포함)
            """;

    private final ExportService service;

    public ExportController(ExportService service) {
        this.service = service;
    }

    @Operation(summary = "내보내기 미리보기",
            description = """
                    **화면: 마이 > 데이터 내보내기 (항목을 고르면 건수와 파일 이름 표시)**

                    파일을 만들지 않고 항목별 건수, 파일 이름, 예상 용량(바이트)만 알려줘요. 아래 `download`와 같은 파라미터를 써요.

                    """ + PARAMS_NOTE)
    @GetMapping("/preview")
    public ApiResponse<Preview> preview(
            @Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId,
            @Parameter(description = "subscriptions, history, payments 중 쉼표로 구분", example = "subscriptions,history,payments")
            @RequestParam(required = false) String items,
            @Parameter(description = "csv 또는 xlsx", example = "csv") @RequestParam(defaultValue = "csv") String format,
            @Parameter(description = "all | 1y | 6m | custom", example = "all") @RequestParam(defaultValue = "all") String period,
            @Parameter(description = "period=custom일 때 시작일 (YYYY-MM-DD)", example = "2026-01-01") @RequestParam(required = false) String from,
            @Parameter(description = "period=custom일 때 종료일 (YYYY-MM-DD)", example = "2026-12-31") @RequestParam(required = false) String to) {
        return ApiResponse.of(service.preview(userId, items, format, period, from, to));
    }

    @Operation(summary = "내보내기 파일 다운로드",
            description = """
                    **화면: 마이 > 데이터 내보내기 ("내보내기" 버튼)**

                    파일(`Content-Disposition: attachment`)을 내려줘요. Swagger에서는 **Download file** 링크로 받을 수 있어요.
                    - CSV는 UTF-8 BOM이 붙어 있어 엑셀에서 한글이 깨지지 않아요. 항목이 여러 개면 `[제목]` 행으로 구역을 나눠요.
                    - XLSX는 항목별로 시트가 나뉘어요.

                    """ + PARAMS_NOTE)
    @GetMapping("/download")
    public ResponseEntity<byte[]> download(
            @Parameter(hidden = true) @RequestAttribute(AuthInterceptor.USER_ID) Long userId,
            @Parameter(description = "subscriptions, history, payments 중 쉼표로 구분", example = "subscriptions,payments")
            @RequestParam(required = false) String items,
            @Parameter(description = "csv 또는 xlsx", example = "csv") @RequestParam(defaultValue = "csv") String format,
            @Parameter(description = "all | 1y | 6m | custom", example = "all") @RequestParam(defaultValue = "all") String period,
            @Parameter(description = "period=custom일 때 시작일 (YYYY-MM-DD)") @RequestParam(required = false) String from,
            @Parameter(description = "period=custom일 때 종료일 (YYYY-MM-DD)") @RequestParam(required = false) String to) {
        FileData file = service.download(userId, items, format, period, from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
