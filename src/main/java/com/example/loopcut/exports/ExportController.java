package com.example.loopcut.exports;

import com.example.loopcut.common.ApiResponse;
import com.example.loopcut.common.AuthInterceptor;
import com.example.loopcut.exports.ExportService.FileData;
import com.example.loopcut.exports.ExportService.Preview;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/exports")
public class ExportController {

    private final ExportService service;

    public ExportController(ExportService service) {
        this.service = service;
    }

    @GetMapping("/preview")
    public ApiResponse<Preview> preview(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                        @RequestParam(required = false) String items,
                                        @RequestParam(defaultValue = "csv") String format,
                                        @RequestParam(defaultValue = "all") String period,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to) {
        return ApiResponse.of(service.preview(userId, items, format, period, from, to));
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                           @RequestParam(required = false) String items,
                                           @RequestParam(defaultValue = "csv") String format,
                                           @RequestParam(defaultValue = "all") String period,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to) {
        FileData file = service.download(userId, items, format, period, from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.content());
    }
}
