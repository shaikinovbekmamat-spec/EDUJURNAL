// OwnerController.java
package com.example.edujurnal.Controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class OwnerController {
    @GetMapping("/owner/dashboard")
    public String dashboard() {
        return "dashboard/owner";
    }
}