package com.example.demo.Service;


import org.springframework.stereotype.Service;

@Service(value = "second")
public class HelloworldServiceSecondImpl implements HelloworldService{
    @Override
    public String tellMeId() {
        return "second";
    }
}
