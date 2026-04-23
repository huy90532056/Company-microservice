package com.embarkx.jobms.job;

import com.embarkx.jobms.job.dto.jobDTO;

import java.util.List;

public interface JobService {
    List<jobDTO> findAll();
    void createJob(Job job);

    jobDTO getJobById(Long id);

    boolean deleteJobById(Long id);

    boolean updateJob(Long id, Job updatedJob);
}
