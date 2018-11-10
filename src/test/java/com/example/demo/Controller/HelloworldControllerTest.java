package com.example.demo.Controller;

import com.example.demo.VO.MyResponseBody;
import org.junit.Test;

import java.util.Optional;

import static org.junit.Assert.*;

public class HelloworldControllerTest {
    @Test
    public void Test(){
        String a = "s";
        String b = "t";
        MyResponseBody myResponseBody = new MyResponseBody();
        System.out.println(Optional.ofNullable(myResponseBody.getContent().getMsg()).orElse("ss"));
        System.out.println("test");
    }
}