package com.example.demo.VO;


import com.example.demo.Valid.UrlValid;
import org.springframework.stereotype.Component;

@Component
public class MyResponseBody {
    String url;
    @UrlValid
    Content content;


    public Content getContent() {
        return content;
    }
}
