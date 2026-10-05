package com.procurepal_services.stock_bridge_api.founding;

import com.procurepal_services.stock_bridge_api.founding.dto.FoundingOfferStatus;
import com.procurepal_services.stock_bridge_api.founding.dto.SetupRequestRequest;
import com.procurepal_services.stock_bridge_api.founding.dto.SetupRequestResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The landing page's two public calls: the offer's live numbers, and booking a setup. */
@RestController
@RequiredArgsConstructor
public class FoundingController {

    private final SetupRequestService setupRequestService;

    @GetMapping("/api/public/founding-offer")
    public FoundingOfferStatus offer() {
        return setupRequestService.status();
    }

    @PostMapping("/api/public/setup-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public SetupRequestResponse request(@Valid @RequestBody SetupRequestRequest request, HttpServletRequest httpRequest) {
        return setupRequestService.request(request, httpRequest.getRemoteAddr());
    }
}
