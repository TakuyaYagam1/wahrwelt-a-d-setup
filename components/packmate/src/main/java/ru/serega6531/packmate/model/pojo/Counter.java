package ru.serega6531.packmate.model.pojo;

import lombok.Getter;

@Getter
public class Counter {

    private int value = 0;

    public void increment() {
        value++;
    }

    public void increment(int num) {
        value += num;
    }

}
