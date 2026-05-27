package com.deepseek.demo;

/**
 * @author: mars.sun
 * @date: 2026/5/25 09:51
 * @description:
 */
public class TestService {
    public static void main(String[] args) {
        int[] nums = new int[]{1, 1, 0, 1, 1, 1};
        int maxCount = 0;
        int tmpCount = 0;
        for (int i = 0; i < nums.length; i++) {
            if (nums[i] == 1) {
                tmpCount++;
                if (i == nums.length - 1) {
                    maxCount = Math.max(maxCount, tmpCount);
                }
            } else {
                maxCount = Math.max(maxCount, tmpCount);
                tmpCount = 0;
            }
        }
    }
}
