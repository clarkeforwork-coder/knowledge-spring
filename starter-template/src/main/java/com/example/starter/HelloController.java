package com.example.starter;

import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    // 參數上的約束註解由 Spring MVC 6.1+ 內建的方法驗證處理，不需要 @Validated
    @GetMapping("/hello")
    public String hello(@RequestParam(defaultValue = "Spring") @Size(max = 20) String name) {
        return "Hello, " + name + "!";
    }
}
