# An extremely convenient tool for cutting and merging video

## Prerequisite

- Make sure [Groovy 2.4+](http://www.groovy-lang.org/download.html)/[JDK 1.7+](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html) are installed.
- Make sure you have `ffmpeg` on `PATH`.

Suppose you're going to cut 00:01:00-00:02:00 and 00:03:00-00:04:00 off and discard them, the following two commands are equivalent:

Keep mode, keep the specified interval:

```
groovy MovieCutter.groovy --input input.mp4 --output output.mp4 -k start-01:00,02:00-03:00,04:00-end --fast
```

Remove mode, remove the specified interval:

```
groovy MovieCutter.groovy -i input.mp4 -r 00:01:00-00:02:00,00:03:00-00:04:00 -s
```

## 先决条件

- 确保安装 [Groovy 2.4+](http://www.groovy-lang.org/download.html)/[JDK 1.7+](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html) are installed.
- 确保`PATH`上有`ffmpeg`

例如，你想要砍掉一个视频中的00:01:00-00:02:00和00:03:00-00:04:00的部分，下列两个命令是等价的：

保留模式，保留指定的时间区间：

```
groovy MovieCutter.groovy --input input.mp4 --output output.mp4 -k start-01:00,02:00-03:00,04:00-end --fast
```

删除模式，删除指定的时间区间

```
groovy MovieCutter.groovy -i input.mp4 -r 00:01:00-00:02:00,00:03:00-00:04:00 -s
```
