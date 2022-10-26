package com.example.nangnang.page.search

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class SearchController {

    @GetMapping
    fun index() = "pubg_search"
}