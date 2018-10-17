package com.example.demo.Service;

import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

@Service(value = "first")
public class HelloworldServiceImpl implements HelloworldService {
    public String tellMeId(){
        return "first";
    }
    @PostConstruct
    public void initialize(){
        System.out.println("initializing");
    }
}
