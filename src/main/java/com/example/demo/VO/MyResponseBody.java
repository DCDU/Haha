package com.example.demo.VO;


import com.example.demo.Valid.UrlValid;
import org.springframework.stereotype.Component;

@Component
public class MyResponseBody {
    String url;
    @UrlValid
    String content;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
