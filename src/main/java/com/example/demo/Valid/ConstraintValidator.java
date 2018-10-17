package com.example.demo.Valid;

import javax.validation.ConstraintValidatorContext;

public class ConstraintValidator implements javax.validation.ConstraintValidator<UrlValid,String> {
    @Override
    public boolean isValid(String s, ConstraintValidatorContext constraintValidatorContext) {
        System.out.println(s);
        if(s.equals("test")){
            return true;
        }
        return false;
    }
}
