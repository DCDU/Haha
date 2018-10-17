package com.example.demo.Controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloworldController{
//    @RequestMapping(value = "/printHelloworld",method = {RequestMethod.GET})
//    String printHelloworld(){
//        return "Hello World !";
//    }
    @GetMapping("/helloworld")
    public String helloworld(){
        return "hello world";
    }
}
