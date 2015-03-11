package com.skiwi.githubhooksechatservice.mvc.beans;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import com.skiwi.githubhooksechatservice.chatbot.ChatBot;
import com.skiwi.githubhooksechatservice.model.TaskData;
import com.skiwi.githubhooksechatservice.mvc.beans.tasks.CommentsScanTask;
import com.skiwi.githubhooksechatservice.mvc.beans.tasks.GithubTask;
import com.skiwi.githubhooksechatservice.mvc.beans.tasks.StatisticTask;
import com.skiwi.githubhooksechatservice.mvc.controllers.GithubHookController;
import com.skiwi.githubhooksechatservice.service.ConfigService;
import com.skiwi.githubhooksechatservice.service.DailyService;
import com.skiwi.githubhooksechatservice.service.GithubService;
import com.skiwi.githubhooksechatservice.service.TaskService;

public class TaskManager {
	
	@Autowired
	private TaskScheduler scheduler;
	
	@Autowired
	private TaskService taskService;
	
    private final GithubEventFilter eventFilter = new GithubEventFilter();
    
    @Autowired private ChatBot chatBot;
    @Autowired private DailyService dailyService;
    @Autowired private ConfigService configService;
    @Autowired private GithubService githubService;
    @Autowired private GithubBean githubBean;
    @Autowired private StackExchangeAPIBean stackAPI;
    @Autowired private GithubHookController controller;
	
	private final List<ScheduledFuture<?>> tasks = new ArrayList<ScheduledFuture<?>>();
	private final List<TaskData> taskData = new ArrayList<TaskData>();
	
	@PostConstruct
	public void startup() {
		reload();
	}
	
	public synchronized void reload() {
		tasks.forEach(f -> f.cancel(false));
		tasks.clear();
		taskData.clear();
		taskData.addAll(taskService.getTasks());
		for (TaskData data : taskData) {
			Runnable runnable = taskToRunnable(data);
			ScheduledFuture<?> future = scheduler.schedule(runnable, new CronTrigger(data.getCron()));
			tasks.add(future);
			System.out.println("Added task: " + runnable);
		}
	}

	private Runnable taskToRunnable(TaskData data) {
		String[] taskInfo = data.getTaskValue().split(";");
		switch (taskInfo[0]) {
			case "dailyStats":
				return new StatisticTask(dailyService, configService, chatBot);
			case "github":
				return new GithubTask(githubService, githubBean, eventFilter, controller);
			case "comments":
				return new CommentsScanTask(stackAPI, chatBot);
			default:
				return () -> System.out.println("Unknown task: " + data.getTaskValue());
		}
	}

	public synchronized List<TaskData> getTasks() {
		return new ArrayList<>(taskData);
	}

	public TaskData add(String cron, String task) {
		return taskService.add(cron, task);
	}
	
}
