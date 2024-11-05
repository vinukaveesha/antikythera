package sa.com.cloudsolutions.antikythera.depsolver;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.VarType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import io.restassured.specification.Argument;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.exception.AntikytheraException;
import sa.com.cloudsolutions.antikythera.exception.DepsolverException;
import sa.com.cloudsolutions.antikythera.generator.CopyUtils;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.ImportWrapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class DepSolver {
    /**
     * The stack for the depth first search.
     */
    private static final LinkedList<GraphNode> stack = new LinkedList<>();

    private final Map<String, Type> names = new HashMap<>();

    /**
     * Main entry point for the dependency solver
     * @throws IOException if files could not be read
     * @throws AntikytheraException if a dependency could not be resolved.
     */
    private void solve() throws IOException, AntikytheraException {
        AbstractCompiler.preProcess();
        Object methods = Settings.getProperty("methods");
        if (methods instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) {
                    processMethod(s);
                }
            }
        }
        else {
            processMethod(methods.toString());
        }
    }

    /**
     * Process the dependencies of a method that was declared in the application configuration
     * @param s the method name
     * @throws AntikytheraException
     */
    private void processMethod(String s) throws AntikytheraException {
        String[] parts = s.split("#");

        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(parts[0] );
        if (cu != null) {
            Optional<MethodDeclaration> method = cu.findAll(MethodDeclaration.class).stream()
                    .filter(m -> m.getNameAsString().equals(parts[1]))
                    .findFirst();

            if (method.isPresent()) {
                Graph.createGraphNode(method.get());
                dfs();
            }
        }
    }

    /**
     * Iterative Depth first search
     * @throws AntikytheraException if any of the code inspections fails.
     */
    private void dfs() throws AntikytheraException {
        while (! stack.isEmpty()) {
            GraphNode node = stack.pollLast();

            if (!node.isVisited()) {
                node.setVisited(true);

                fieldSearch(node);
                methodSearch(node);
                constructorSearch(node);
            }
        }
    }

    /**
     * Search in methods.
     * The return type, all the locals declared inside the method and arguments are searchable.
     * There maybe decorators for the method or some of the arguments. These need to be searched as
     * well.
     * @param node A graph node that represents a method in the code.
     */
    private void methodSearch(GraphNode node) throws AntikytheraException {
        if (node.getEnclosingType() != null) {

            String className = node.getEnclosingType().getNameAsString();
            Optional<ClassOrInterfaceDeclaration> c = node.getDestination().findFirst(ClassOrInterfaceDeclaration.class,
                    t -> t.getNameAsString().equals(className));

            if (node.getNode() instanceof MethodDeclaration md) {
                if (c.isPresent()) {
                    node.getTypeDeclaration().addMember(md);
                }

                searchMethodParameters(node, md.getParameters());
                Type returnType = md.getType();
                String returns = md.getTypeAsString();
                if (!returns.equals("void") && returnType.isClassOrInterfaceType()) {
                    node.addTypeArguments(returnType.asClassOrInterfaceType());
                }

                names.clear();
                md.accept(new VariableVisitor(), node);
                md.accept(new Visitor(), node);
            }
        }
    }

    /**
     * Search in constructors.
     * All the locals declared inside the constructor and arguments are searchable.
     * Any annotations for the arguments or the constructor will be searched as well.
     * @param node A graph node that represents a constructor
     */
    private void constructorSearch(GraphNode node) throws AntikytheraException {
        if (node.getEnclosingType() != null && node.getNode() instanceof ConstructorDeclaration cd) {
            searchMethodParameters(node, cd.getParameters());
            cd.accept(new Visitor(), node);
        }
    }

    /**
     * Search method parameters for dependencies.
     * @param node GraphNode representing a method.
     * @param parameters the list of paremeters of that method
     * @throws AntikytheraException if some resolution problem crops up
     */
    private void searchMethodParameters(GraphNode node, NodeList<Parameter> parameters) throws AntikytheraException {
        for(Parameter p : parameters) {
            List<ImportWrapper> imports = AbstractCompiler.findImport(node.getCompilationUnit(), p.getType());
            for(ImportWrapper imp : imports) {
                searchClass(node, imp);
            }

            for(AnnotationExpr ann : p.getAnnotations()) {
                ImportWrapper imp2 = AbstractCompiler.findImport(node.getCompilationUnit(), ann.getNameAsString());
                searchClass(node, imp2);
            }
        }
    }

    /**
     * Search for an outgoing edge to another class
     * @param node the current node
     * @param imp the import declaration for the other class.
     * @throws AntikytheraException
     */
    private void searchClass(GraphNode node, ImportWrapper imp) throws AntikytheraException {
        /*
         * It is likely that this is a DTO an Entity or a model. So we will assume that all the
         * fields are required along with their respective annotations.
         */
        if (imp != null) {
            node.getDestination().addImport(imp.getImport());

            TypeDeclaration<?> decl = imp.getType();
            if (decl != null) {
                Graph.createGraphNode(decl);
            }
            node.getDestination().addImport(imp.getImport());
        }
    }

    private void fieldSearch(GraphNode node) throws AntikytheraException {
        if(node.getNode() instanceof FieldDeclaration fd) {
            for(AnnotationExpr ann : fd.getAnnotations()) {
                ImportWrapper imp = AbstractCompiler.findImport(node.getCompilationUnit(), ann.getNameAsString());
                if (imp != null) {
                    node.getDestination().addImport(imp.getImport());
                }
            }
            if(node.getTypeDeclaration().getFieldByName(fd.getVariable(0).getNameAsString()).isEmpty()) {
                node.addField(fd);
            }
        }
    }

    private void sortClass(ClassOrInterfaceDeclaration classOrInterface) {
        List<FieldDeclaration> fields = classOrInterface.getMembers().stream()
                .filter(FieldDeclaration.class::isInstance)
                .map(FieldDeclaration.class::cast)
                .sorted(Comparator.comparing(f -> f.getVariable(0).getNameAsString()))
                .toList();

        List<MethodDeclaration> methods = classOrInterface.getMembers().stream()
                .filter(MethodDeclaration.class::isInstance)
                .map(MethodDeclaration.class::cast)
                .sorted(Comparator.comparing(MethodDeclaration::getNameAsString))
                .toList();

        // Clear original members
        classOrInterface.getMembers().clear();

        // Add sorted fields and methods back
        classOrInterface.getMembers().addAll(fields);
        classOrInterface.getMembers().addAll(methods);
    }

    private void writeFiles() throws IOException {
        Files.copy(Paths.get(Settings.getProperty("base_path").toString().replace("src/main/java",""), "pom.xml"),
                Paths.get(Settings.getProperty("output_path").toString().replace("src/main/java",""), "pom.xml"),
                StandardCopyOption.REPLACE_EXISTING);

        for (Map.Entry<String, CompilationUnit> entry : Graph.getDependencies().entrySet()) {
            CompilationUnit cu = entry.getValue();
            List<ImportDeclaration> list = new ArrayList<>(cu.getImports());
            cu.getImports().clear();
            list.sort(Comparator.comparing(NodeWithName::getNameAsString));
            cu.getImports().addAll(list);

            for(TypeDeclaration<?> decl : cu.getTypes()) {
                if (decl.isClassOrInterfaceDeclaration()) {
                    sortClass(decl.asClassOrInterfaceDeclaration());
                }
            }
            CopyUtils.writeFile(
                        AbstractCompiler.classToPath(entry.getKey()), cu.toString());
        }
    }


    private class VariableVisitor extends VoidVisitorAdapter<GraphNode> {

        @Override
        public void visit(final Parameter n, GraphNode node) {
            names.put(n.getNameAsString(), n.getType());

            solveType(n.getType(), node);
            super.visit(n, node);
        }

        @Override
        public void visit(final VariableDeclarationExpr n, GraphNode node) {
            for(VariableDeclarator vd : n.getVariables()) {
                names.put(vd.getNameAsString(), vd.getType());
                solveType(vd.getType(), node);
                Optional<Expression> init = vd.getInitializer();
                if (init.isPresent()) {
                    if (init.get().isNameExpr()) {
                        ImportWrapper imp = AbstractCompiler.findImport(node.getCompilationUnit(), init.get().asNameExpr().getNameAsString());
                        if (imp != null) {
                            try {
                                node.getDestination().addImport(imp.getImport());
                                if (imp.getType() != null) {
                                    Graph.createGraphNode(imp.getType());
                                }
                                if (imp.getField() != null) {
                                    Graph.createGraphNode(imp.getField());
                                }
                            } catch (AntikytheraException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }
                }
            }
            super.visit(n, node);
        }
    }

    private void solveType(Type vd, GraphNode node) {
        if (vd.isClassOrInterfaceType()) {
            List<ImportWrapper> imports = AbstractCompiler.findImport(node.getCompilationUnit(), vd);
            for (ImportWrapper imp : imports) {
                try {
                    searchClass(node, imp);
                    FieldDeclaration fieldDeclaration = imp.getField();
                    if (fieldDeclaration != null) {
                        Graph.createGraphNode(fieldDeclaration);
                    }
                } catch (AntikytheraException e) {
                    throw new DepsolverException(e);
                }
            }
        }
    }

    private class Visitor extends AnnotationVisitor {
        @Override
        public void visit(MethodCallExpr mce, GraphNode node) {

            Optional<Expression> scope = mce.getScope();
            try {
                if(scope.isPresent()) {
                     externalMethod(node, scope.get(), mce);
                     scope.get().accept(new VariableVisitor(), node);
                     scope.get().accept(new Visitor(), node);

                }
                else {
                    if (node.getEnclosingType().isClassOrInterfaceDeclaration()) {
                        Optional<MethodDeclaration> md = AbstractCompiler.findMethodDeclaration(
                                mce, node.getEnclosingType().asClassOrInterfaceDeclaration()
                        );
                        if(md.isPresent()) {
                            Graph.createGraphNode(md.get());
                        }
                    }
                }

                for (Expression arg : mce.getArguments()) {
                    if(arg.isFieldAccessExpr()) {
                        resolveField(node, arg);
                    }
                    else if(arg.isNameExpr()) {
                        if (!names.containsKey(arg.toString())) {
                            ImportWrapper imp = AbstractCompiler.findImport(node.getCompilationUnit(), arg.asNameExpr().getNameAsString());
                            if (imp != null) {
                                node.getDestination().addImport(imp.getImport());
                                if (imp.getField() != null) {
                                    Graph.createGraphNode(imp.getField());
                                }
                            }
                        }
                    } else if(arg.isMethodCallExpr()) {
                        visit(arg.asMethodCallExpr(), node);
                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
                throw new DepsolverException("aa");
            }

            super.visit(mce, node);
        }
        /**
         * Resolve dependencies for an object creation expression
         * @param oce the object creation expression
         * @param node the graph node.
         */
        @Override
        public void visit(ObjectCreationExpr oce, GraphNode node) {
            solveType(oce.getType(), node);
            for(Expression arg : oce.getArguments()) {
                if(arg.isFieldAccessExpr()) {
                    resolveField(node, arg);
                }
                else if(arg.isMethodCallExpr()) {
                    Optional<Expression> scope = arg.asMethodCallExpr().getScope();
                    if (scope.isPresent()) {
                        scope.get().accept(new VariableVisitor(), node);
                        scope.get().accept(new Visitor(), node);

                        if (scope.get().isNameExpr()) {
                            ImportWrapper imp = AbstractCompiler.findImport(node.getCompilationUnit(),
                                    scope.get().asNameExpr().getNameAsString());
                            try {
                                searchClass(node, imp);
                            } catch (AntikytheraException e) {
                                throw new DepsolverException(e);
                            }

                            /*
                             * We need to find the method declaration and then add it to the stack.
                             * First step is to find the CompilationUnit. We cannot rely on using the
                             * import declaration as the other class maybe in the same package and may
                             * not have an import.
                             */
                            CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(
                                    AbstractCompiler.findFullyQualifiedName(node.getCompilationUnit(),
                                            scope.get().asNameExpr().getNameAsString()));

                            if (cu != null) {
                                Optional<ClassOrInterfaceDeclaration> cdecl = cu.findFirst(ClassOrInterfaceDeclaration.class,
                                        c -> c.getNameAsString().equals(scope.get().asNameExpr().getNameAsString()));

                                if (cdecl.isPresent()) {
                                    AbstractCompiler.findMethodDeclaration(
                                            arg.asMethodCallExpr(), cdecl.get()
                                    ).ifPresent(md -> {
                                        try {
                                            Graph.createGraphNode(md);
                                        } catch (AntikytheraException e) {
                                            throw new DepsolverException(e);
                                        }
                                    });
                                }
                            }
                        }
                        else if (scope.get().isFieldAccessExpr()) {
                            resolveField(node, scope.get());
                        }
                    }
                }
            }
            super.visit(oce, node);
        }

        /**
         * Handles calling an external method.
         * An external method will typically have a field, a local variable or a method parameter as
         * scope. If the scope is a field, that will be preserved.
         * @param node a graph node that represents a method in the code.
         * @param scope the scope of the method call.
         * @param mce the method call expression
         * @throws AntikytheraException
         */
        private void externalMethod(GraphNode node, Expression scope, MethodCallExpr mce) throws AntikytheraException {
            TypeDeclaration<?> cdecl = node.getEnclosingType();

            if (scope.isNameExpr()) {

                NameExpr expr = scope.asNameExpr();
                Optional<FieldDeclaration> fd = cdecl.getFieldByName(expr.getNameAsString());

                Type field = null;

                if (fd.isPresent()) {
                    field = fd.get().getElementType();
                    node.addField(fd.get());

                }
                else {
                    field = names.get(expr.getNameAsString());
                }


                if(field != null) {
                    for(AnnotationExpr ann : field.getAnnotations()) {
                        ImportWrapper imp = AbstractCompiler.findImport(node.getCompilationUnit(), ann.getNameAsString());
                        if (imp != null) {
                            node.getDestination().addImport(imp.getImport());
                        }
                    }

                }
                else {
                    /*
                     * Can be either a call related to a local or a static call
                     */
                    ImportWrapper imp = AbstractCompiler.findImport(node.getCompilationUnit(), expr.getNameAsString());
                    if (imp != null) {
                        node.getDestination().addImport(imp.getImport());
                    }
                }
            }
            else if (scope.isFieldAccessExpr()) {
                resolveField(node, scope);
            }
        }

    }


    public static void main(String[] args) throws IOException, AntikytheraException {
        File yamlFile = new File(Settings.class.getClassLoader().getResource("depsolver.yml").getFile());
        Settings.loadConfigMap(yamlFile);
        DepSolver depSolver = new DepSolver();
        depSolver.solve();

        CopyUtils.createMavenProjectStructure(Settings.getBasePackage(), Settings.getProperty("output_path").toString());
        depSolver.writeFiles();
    }

    public static void push(GraphNode g) {
        stack.push(g);
    }
}
