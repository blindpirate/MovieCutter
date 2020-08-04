import groovy.cli.commons.CliBuilder
import groovy.cli.commons.OptionAccessor
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

import java.nio.file.Paths

class MovieCutter {
    static void main(String[] args) {

        def cli = new CliBuilder(usage: '''
---------------------------------------------------------------------------------------------------
-i/--input:                         the input file
-o/--output (optional):             the output file
-f/--fast (optional):               use fast but inaccurate algorithm
-s/--slow (optional, default):      use slow but accurate algorithm
-k/--keep (keep mode):              keep the specified interval
-r/--remove (remove mode):          remove the specified interval
-d/--dry-run (optional):            only print the commands to be executed but not execute them

Examples: suppose you're going to cut 00:01:00-00:02:00 and 00:03:00-00:04:00 off and discard them, the following two commands are equivalent:

Keep mode, keep the specified interval:

groovy MovieCutter.groovy --input input.mp4 --output output.mp4 -k start-01:00,02:00-03:00,04:00-end --fast

Remove mode, remove the specified interval:

groovy MovieCutter.groovy -i input.mp4 -r 00:01:00-00:02:00,00:03:00-00:04:00 -s

---------------------------------------------------------------------------------------------------
-i/--input:                         输入文件
-o/--output (optional):             输出文件
-f/--fast (optional):               快速但不精确的算法
-s/--slow (optional, default):      慢但精确的算法
-k/--keep (keep mode):              保留指定的时间区间
-r/--remove (remove mode):          删除指定的时间区间
-d/--dry-run (optional):            只打印要执行的命令但不执行

例如，你想要砍掉一个视频中的00:01:00-00:02:00和00:03:00-00:04:00的部分，下列两个命令是等价的：

保留模式，保留指定的时间区间：

groovy MovieCutter.groovy --input input.mp4 --output output.mp4 -k start-01:00,02:00-03:00,04:00-end --fast

删除模式，删除指定的时间区间

groovy MovieCutter.groovy -i input.mp4 -r 00:01:00-00:02:00,00:03:00-00:04:00 -s
---------------------------------------------------------------------------------------------------
''')

        cli.with {
            i(longOpt: 'input', 'the input file', args: 1, required: true)
            r(longOpt: 'remove', 'Time intervals to cut off', args: 1, required: false)
            k(longOpt: 'keep', 'Time intervals to keep', args: 1, required: false)
            f(longOpt: 'fast', 'Use fast algorithm', args: 0, required: false)
            s(longOpt: 'slow', 'Use slow algorithm', args: 0, required: false)
            o(longOpt: 'output', 'the output file', args: 1, required: false)
            d(longOpt: 'dry-run', 'print commands but not execute', args: 0, required: false)
        }

        cli.width = 1000

        def options = cli.parse(args)

        if (!options) {
            return
        }
        if (!options.i) {
            cli.usage()
            return
        }

        if (!options.r && !options.k) {
            cli.usage()
            return
        }

        if (options.r && options.k) {
            System.err.println("-k/--keep and -r/--remove are not allowed to exist at the same time!")
            cli.usage()
            return
        }

        List<File> cutFiles = cut(options)
        merge(options, cutFiles)
    }

    static File getInputFile(OptionAccessor options) {
        return Paths.get(options.i).toFile().absoluteFile
    }

    static void merge(OptionAccessor options, List<File> tmpFiles) {
        File outputFile = determineOutput(options)
        if (tmpFiles.size() == 1) {
            if (options.d) {
                System.out.println("Rename ${tmpFiles[0].absolutePath} to ${outputFile.absolutePath}")
            }
            tmpFiles[0].renameTo(outputFile)
        } else {
            File tmpFile = File.createTempFile('movieMerge', '.txt')
            tmpFile.text = tmpFiles.collect { "file '${it.absoluteFile}'" }.join('\n')
            run(options, 'ffmpeg', '-f', 'concat', '-safe', '0', '-i', tmpFile.absolutePath, '-c', 'copy', outputFile.absolutePath)
            tmpFiles.each { assert it.delete() }
        }
    }

    static List<File> cut(OptionAccessor options) {
        File inputFile = getInputFile(options)
        assert inputFile.isFile(): "${options.i} is not a file!"
        List<Interval> intervals = keepModeIntervals(options, inputFile)
        return intervals.withIndex().collect { Interval interval, int index ->
            File tmp = addExtensionPrefix(inputFile, index.toString())
            List args = ['ffmpeg', '-i', inputFile.absolutePath, '-ss', interval.start, '-to', interval.end]
            if (isFastAlgorithm(options)) {
                args += ['-c', 'copy']
            }
            args += [tmp.absolutePath]
            run(options, args as String[])
            tmp
        }
    }

    static List<Interval> keepModeIntervals(OptionAccessor options, File inputFile) {
        if (options.k) {
            return parseInterval(options.k, inputFile)
        } else {
            List<Interval> removeModeIntervals = parseInterval(options.r, inputFile)
            return convertToKeepMode(removeModeIntervals, inputFile)
        }
    }

    static List<Interval> convertToKeepMode(List<Interval> removeModeIntervals, File inputFile) {
        // 01-02,03-04 -> start-01,02-03,04-end
        assert !removeModeIntervals.empty

        // 0:0-0:1,0:2-0:3 -> 0:1-0:2,0:3-end
        List<Interval> ret = removeModeIntervals[0].start == Instant.ZERO ?
                [] :
                [new Interval(start: Instant.parse('start', inputFile), end: removeModeIntervals[0].start)]
        for (int i = 0; i < removeModeIntervals.size() - 1; ++i) {
            ret.add(new Interval(start: removeModeIntervals[i].end, end: removeModeIntervals[i + 1].start))
        }

        Instant lastIntervalStart = removeModeIntervals[-1].end
        Instant end = Instant.parse('end', inputFile)
        if (lastIntervalStart != end) {
            ret.add(new Interval(start: lastIntervalStart, end: end))
        }
        return ret
    }

    static boolean isFastAlgorithm(OptionAccessor options) {
        return options.f
    }

    static File addExtensionPrefix(File originalFile, String prefix) {
        // input.mp4, output -> input.output.mp4
        // input.mp4, 0 -> input.0.mp4

        int lastIndexOfDot = originalFile.name.lastIndexOf('.')
        String newFileName = "${originalFile.name[0..<lastIndexOfDot]}.${prefix}${originalFile.name[lastIndexOfDot..-1]}"
        return new File(originalFile.parentFile, newFileName)
    }

    static List<Interval> parseInterval(String intervals, File inputFile) {
        return intervals.split(',').collect { Interval.parse(it, inputFile) }
    }

    @ToString
    @EqualsAndHashCode
    static class Interval {
        Instant start
        Instant end

        static Interval parse(String intervalString, File inputFile) {
            // start-01:00
            // 00:00:00-00:01:00
            // start-end
            // 00:00-end
            assert intervalString.count('-') == 1: "Unrecognize: ${intervalString}"

            String startTime = intervalString.split('-')[0]
            String endTime = intervalString.split('-')[1]

            return new Interval(start: Instant.parse(startTime, inputFile), end: Instant.parse(endTime, inputFile))
        }
    }

    @ToString
    @EqualsAndHashCode
    static class Instant {
        static final Instant ZERO = Instant.fromHourMinuteSecond('0:0:0')
        int hour = 0
        int minute = 0
        int second = 0

        static Instant parse(String instantString, File inputFile) {
            // start
            // end
            // 00:00
            // 00:00:00
            switch (instantString) {
                case 'start':
                    return new Instant()
                case 'end':
                    return getDuration(inputFile)
                case ~/\d+:\d+/:
                    return new Instant(
                            minute: instantString.split(':')[0].toDouble().toInteger(),
                            second: instantString.split(':')[1].toDouble().toInteger(),
                    )
                case ~/\d+:\d+:\d+/:
                    return fromHourMinuteSecond(instantString)
                default:
                    assert false: "Unrecognize: ${instantString}"
            }
        }

        static Instant fromHourMinuteSecond(String s) {
            // 0:32:52.900000
            assert s.count(':') == 2: "Unrecognize ${s}"
            String[] times = s.split(':')
            return new Instant(hour: times[0].toDouble().toInteger(),
                    minute: times[1].toDouble().toInteger(),
                    second: times[2].toDouble().toInteger()
            )
        }

        static Instant getDuration(File inputFile) {
            // TODO this is a little inefficient, but we expect only there's only one "end" in the whole time string
            String duration = runAndGetStdout('ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-sexagesimal', '-of', 'default=noprint_wrappers=1:nokey=1', inputFile.absolutePath)
            return fromHourMinuteSecond(duration)
        }


        @Override
        String toString() {
            return String.format("%02d:%02d:%02d", hour, minute, second)
        }
    }

    static File determineOutput(OptionAccessor options) {
        if (options.o) {
            return Paths.get(options.o).toFile()
        } else {
            return addExtensionPrefix(getInputFile(options), 'output')
        }
    }

    static String runAndGetStdout(Object... args) {
        println("Running: ${args.join(' ')}")
        Process process = new ProcessBuilder().command(args.collect { it.toString() }).start()
        String stdout = process.in.text.trim()
        println("Get stdout: ${stdout}")
        return stdout
    }

    static void run(OptionAccessor options, String... args) {
        println("Running: ${args.join(' ')}")
        if (options.d) {
            return
        }
        ProcessBuilder pb = new ProcessBuilder().command(args).inheritIO()
        assert pb.start().waitFor() == 0: "${args.join(' ')} return non-zero code!"
    }
}
