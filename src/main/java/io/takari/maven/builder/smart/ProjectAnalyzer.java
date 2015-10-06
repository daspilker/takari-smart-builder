package io.takari.maven.builder.smart;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProjectAnalyzer {
    private final static Logger logger = LoggerFactory.getLogger(ProjectAnalyzer.class);

    private static Comparator<Node> SERVICE_TIME_COMPARATOR = new Comparator<Node>() {
        @Override
        public int compare(Node o1, Node o2) {
            return (int) (o1.serviceTime - o2.serviceTime);
        }
    };

    public static void analyze(MavenSession session) {
        Map<String, Long> historicalServiceTimes = ProjectComparator.readServiceTimes(session);
        long defaultServiceTime = ProjectComparator.average(historicalServiceTimes.values());

        List<Node> roots = buildGraph(session, historicalServiceTimes, defaultServiceTime);

        List<Node> criticalPath = new ArrayList<>();
        for (Node root : roots) {
            if (getServiceTime(root.criticalPath()) > getServiceTime(criticalPath)) {
                criticalPath = root.criticalPath();
            }
        }

        logger.info("Project count: " + session.getProjectDependencyGraph().getSortedProjects().size());
        logger.info("Average build time: " + defaultServiceTime);
        logger.info("Critical path:");
        for (Node node : criticalPath) {
            logger.info("  " + node.project + " " + node.serviceTime + "ms");
        }
        logger.info("Length of critical path: " + criticalPath.size());
        logger.info("Total time on critical path: " + getServiceTime(criticalPath) + "ms");
        logger.info("Modules on critical path with build time twice above average:");
        Collections.sort(criticalPath, SERVICE_TIME_COMPARATOR);
        for (Node node : criticalPath) {
            if (node.serviceTime > 2 * defaultServiceTime) {
                logger.info("  " + node.project + " " + node.serviceTime + "ms");
            }
        }
    }

    private static List<Node> buildGraph(MavenSession session,
                                         Map<String, Long> historicalServiceTimes, long defaultServiceTime) {
        ProjectDependencyGraph dependencyGraph = session.getProjectDependencyGraph();

        Map<MavenProject, Node> nodes = new HashMap<>();
        List<Node> roots = new ArrayList<>();
        for (MavenProject project : dependencyGraph.getSortedProjects()) {
            long serviceTime = ProjectComparator.getServiceTime(historicalServiceTimes, project, defaultServiceTime);
            Node node = new Node(project, serviceTime);
            nodes.put(project, node);

            List<MavenProject> upstreamProjects = dependencyGraph.getUpstreamProjects(project, false);
            if (upstreamProjects.isEmpty()) {
                roots.add(node);
            }
            for (MavenProject upstreamProject : upstreamProjects) {
                nodes.get(upstreamProject).children.add(node);
            }
        }
        return roots;
    }

    private static long getServiceTime(List<Node> nodes) {
        long result = 0;
        for (Node node : nodes) {
            result += node.serviceTime;
        }
        return result;
    }

    static class Node {
        final MavenProject project;
        final List<Node> children = new ArrayList<>();
        final long serviceTime;

        List<Node> criticalPath;

        public Node(MavenProject project, long serviceTime) {
            this.project = project;
            this.serviceTime = serviceTime;
        }

        public List<Node> criticalPath() {
            if (criticalPath == null) {
                criticalPath = new ArrayList<>();
                for (Node child : children) {
                    List<Node> nodes = child.criticalPath();
                    if (getServiceTime(nodes) > getServiceTime(criticalPath)) {
                        criticalPath.clear();
                        criticalPath.addAll(nodes);
                    }
                }
                criticalPath.add(0, this);
            }
            return criticalPath;
        }
    }
}
