package com.procurepal_services.stock_bridge_api.demo;

/** Something a real company may do but the shared demo company may not (V86). Answered as 403. */
public class DemoActionNotAllowedException extends RuntimeException {

    public DemoActionNotAllowedException(String message) {
        super(message);
    }
}
