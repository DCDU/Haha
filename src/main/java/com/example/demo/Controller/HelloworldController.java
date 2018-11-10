package com.example.demo.Controller;

import com.example.demo.Service.HelloworldService;
import com.example.demo.VO.MyResponseBody;
import com.example.demo.Valid.UrlValid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import javax.validation.*;
import java.util.Set;

@RestController
public class HelloworldController{
    @Autowired
    @Qualifier("first")
    private HelloworldService helloworldService;
    @RequestMapping(value = "/printHelloworld",method = {RequestMethod.GET})
    String printHelloworld(MyResponseBody myResponseBody){
        ValidatorFactory vf=Validation.buildDefaultValidatorFactory();
        Validator validator  =  vf.getValidator();
        Set<ConstraintViolation<MyResponseBody>> s =  validator.validate(myResponseBody);
        for(ConstraintViolation cv :s){
            System.out.println(cv);
        }
        return "Hello "+myResponseBody.getContent()+" World !";
    }
    @RequestMapping(value = "/printServiceId",method = {RequestMethod.GET})
    String printServiceId(){
        return helloworldService.tellMeId();
    }
}
