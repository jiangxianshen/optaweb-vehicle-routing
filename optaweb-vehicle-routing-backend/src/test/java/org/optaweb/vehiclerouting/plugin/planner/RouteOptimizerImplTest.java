/*
 * Copyright 2019 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.optaweb.vehiclerouting.plugin.planner;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;
import org.mockito.stubbing.Answer1;
import org.mockito.stubbing.VoidAnswer1;
import org.optaplanner.core.api.score.buildin.hardsoftlong.HardSoftLongScore;
import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.event.BestSolutionChangedEvent;
import org.optaplanner.examples.vehiclerouting.domain.Customer;
import org.optaplanner.examples.vehiclerouting.domain.Depot;
import org.optaplanner.examples.vehiclerouting.domain.Standstill;
import org.optaplanner.examples.vehiclerouting.domain.VehicleRoutingSolution;
import org.optaweb.vehiclerouting.domain.LatLng;
import org.optaweb.vehiclerouting.domain.Location;
import org.optaweb.vehiclerouting.domain.Route;
import org.optaweb.vehiclerouting.service.location.DistanceMatrix;
import org.optaweb.vehiclerouting.service.route.RouteChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.AsyncTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.answer;
import static org.mockito.AdditionalAnswers.answerVoid;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.optaweb.vehiclerouting.plugin.planner.SolutionUtil.planningLocation;

@RunWith(MockitoJUnitRunner.class)
public class RouteOptimizerImplTest {

    private final Location location1 = new Location(1, LatLng.valueOf(1.0, 0.1));
    private final Location location2 = new Location(2, LatLng.valueOf(0.2, 2.2));
    private final Location location3 = new Location(3, LatLng.valueOf(3.4, 5.6));

    private boolean isSolving;

    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private Solver<VehicleRoutingSolution> solver;
    @Mock
    private BestSolutionChangedEvent<VehicleRoutingSolution> bestSolutionChangedEvent;
    @Captor
    private ArgumentCaptor<RouteChangedEvent> routeChangedEventArgumentCaptor;
    @Mock
    private DistanceMatrix distanceMatrix;
    @Mock
    private AsyncTaskExecutor executor;
    @Mock
    private Future solverFuture;
    @InjectMocks
    private RouteOptimizerImpl routeOptimizer;

    @Before
    public void setUp() {
        // always run the runnable submitted to executor (that's what every executor does)
        // we can then verify that solver.solve() has been called
        when(executor.submit(any(Runnable.class))).thenAnswer(answer((Answer1<Future, Runnable>) runnable -> {
            runnable.run();
            return solverFuture;
        }));

        // mimic solve() => isSolving(); terminateEarly() => !isSolving()
        isSolving = false;
        when(solver.isSolving()).thenAnswer((Answer<Boolean>) invocation -> isSolving);
        when(solver.solve(any())).thenAnswer(answerVoid((VoidAnswer1<VehicleRoutingSolution>) solution -> isSolving = true));
        when(solver.terminateEarly()).thenAnswer((Answer<Boolean>) invocation -> {
            isSolving = false;
            return true;
        });
    }

    @Test
    public void should_listen_for_best_solution_events() {
        verify(solver).addEventListener(routeOptimizer);
    }

    @Test
    public void ignore_new_best_solutions_when_unprocessed_fact_changes() {
        // arrange
        when(bestSolutionChangedEvent.isEveryProblemFactChangeProcessed()).thenReturn(false);

        // act
        routeOptimizer.bestSolutionChanged(bestSolutionChangedEvent);

        // assert
        verify(bestSolutionChangedEvent, never()).getNewBestSolution();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    public void publish_new_best_solution_if_all_fact_changes_processed() {
        VehicleRoutingSolution solution = createSolution(location1, location2);
        when(bestSolutionChangedEvent.isEveryProblemFactChangeProcessed()).thenReturn(true);
        when(bestSolutionChangedEvent.getNewBestSolution()).thenReturn(solution);

        routeOptimizer.bestSolutionChanged(bestSolutionChangedEvent);

        verify(eventPublisher).publishEvent(routeChangedEventArgumentCaptor.capture());
        RouteChangedEvent event = routeChangedEventArgumentCaptor.getValue();

        assertThat(event.depot()).contains(location1);
        assertThat(event.routes()).isNotEmpty();
        for (Route route : event.routes()) {
            assertThat(route.depot()).isEqualTo(location1);
            assertThat(route.visits()).containsExactly(location2);
        }
    }

    @Test
    public void solution_with_depot_and_no_visits_should_be_published() {
        routeOptimizer.addLocation(location1, distanceMatrix);

        verify(eventPublisher).publishEvent(routeChangedEventArgumentCaptor.capture());
        RouteChangedEvent event = routeChangedEventArgumentCaptor.getValue();

        assertThat(solver.isSolving()).isFalse();
        assertThat(event.depot()).contains(location1);
        assertThat(event.routes()).hasSameSizeAs(SolutionUtil.initialSolution().getVehicleList());
        for (Route route : event.routes()) {
            assertThat(route.depot()).isEqualTo(location1);
            assertThat(route.visits()).isEmpty();
        }
    }

    @Test
    public void solver_should_start_when_two_locations_added() {
        // add 2 locations
        routeOptimizer.addLocation(location1, distanceMatrix);
        routeOptimizer.addLocation(location2, distanceMatrix);

        assertThat(isSolving).isTrue();
        assertThat(solver.isSolving()).isTrue();
        // solving has started after adding a second location (depot + visit)
        verify(solver).solve(any());

        // problem fact change only happens when adding location to a running solver
        // or when more than 1 location remains after removing one
        verify(solver, never()).addProblemFactChange(any());
    }

    @Test
    public void solver_should_stop_when_locations_reduced_to_one() throws ExecutionException, InterruptedException {
        // add 2 locations
        routeOptimizer.addLocation(location1, distanceMatrix);
        routeOptimizer.addLocation(location2, distanceMatrix);

        // remove 1 location from running solver
        assertThat(solver.isSolving()).isTrue();
        routeOptimizer.removeLocation(location2);

        assertThat(solver.isSolving()).isFalse();
        verify(solver).terminateEarly();
        verify(solverFuture).get();

        // problem fact change only happens when adding location to a running solver
        // or when more than 1 location remains after removing one
        verify(solver, never()).addProblemFactChange(any());
    }

    @Test
    public void solution_update_event_should_only_have_empty_routes_when_last_visit_removed() {
        // FIXME This test shouldn't be needed. This is a problem with bad encapsulation of the planning domain in
        //   optaplanner-examples. Once we introduce our own planning domain with a better API, the test should be
        //   replaced/simplified/removed.

        // Prepare a solution with 1 depot, 2 vehicles, 1 customer and both vehicles visiting to that customer
        VehicleRoutingSolution solution = SolutionUtil.emptySolution();
        Depot depot = SolutionUtil.addDepot(solution, planningLocation(location1));
        SolutionUtil.addVehicle(solution, 1);
        SolutionUtil.addVehicle(solution, 2);
        SolutionUtil.moveAllVehiclesTo(solution, depot);
        Customer customer = SolutionUtil.addCustomer(solution, planningLocation(location2));
        solution.getVehicleList().forEach(vehicle -> vehicle.setNextCustomer(customer));
        assertThat(SolutionUtil.routes(solution)).allMatch(route -> route.visits().size() == 1);
        solution.setScore(HardSoftLongScore.ofSoft(-1000)); // set non-zero travel distance

        // Start solver by adding two locations
        routeOptimizer.addLocation(location1, distanceMatrix);
        routeOptimizer.addLocation(location2, distanceMatrix);

        // Pretend solver found a new best best solution
        when(bestSolutionChangedEvent.isEveryProblemFactChangeProcessed()).thenReturn(true);
        when(bestSolutionChangedEvent.getNewBestSolution()).thenReturn(solution);
        routeOptimizer.bestSolutionChanged(bestSolutionChangedEvent);

        clearInvocations(eventPublisher);

        routeOptimizer.removeLocation(location2);
        assertThat(routeOptimizer.isSolving()).isFalse();

        verify(eventPublisher).publishEvent(routeChangedEventArgumentCaptor.capture());
        RouteChangedEvent event = routeChangedEventArgumentCaptor.getValue();

        // no customer -> all routes should be empty
        assertThat(event.distance()).isEqualTo("0h 0m 0s"); // expect zero travel distance
        assertThat(event.depot()).isPresent();
        assertThat(event.routes()).hasSameSizeAs(solution.getVehicleList());
        for (Route route : event.routes()) {
            assertThat(route.visits()).isEmpty();
        }
    }

    @Test
    public void removing_depot_impossible_when_there_are_other_locations() {
        // add 2 locations
        routeOptimizer.addLocation(location1, distanceMatrix);
        routeOptimizer.addLocation(location2, distanceMatrix);

        assertThatThrownBy(() -> routeOptimizer.removeLocation(location1))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("depot");
    }

    @Test
    public void adding_location_to_running_solver_must_happen_through_problem_fact_change() {
        routeOptimizer.addLocation(location1, distanceMatrix);
        assertThat(solver.isSolving()).isFalse();
        routeOptimizer.addLocation(location2, distanceMatrix);
        assertThat(solver.isSolving()).isTrue();
        routeOptimizer.addLocation(location3, distanceMatrix);
        verify(solver).addProblemFactChange(any());
    }

    @Test
    public void removing_location_from_solver_with_more_than_two_locations_must_happen_through_problem_fact_change() {
        // set up a situation where solver is running with 1 depot and 2 visits
        VehicleRoutingSolution solution = createSolution(location1, location2, location3);
        when(bestSolutionChangedEvent.isEveryProblemFactChangeProcessed()).thenReturn(true);
        when(bestSolutionChangedEvent.getNewBestSolution()).thenReturn(solution);
        routeOptimizer.addLocation(location1, distanceMatrix);
        routeOptimizer.addLocation(location2, distanceMatrix);
        routeOptimizer.addLocation(location3, distanceMatrix);
        routeOptimizer.bestSolutionChanged(bestSolutionChangedEvent);

        routeOptimizer.removeLocation(location2);
        verify(solver).addProblemFactChanges(any()); // note that it's plural
        // solver still running
        verify(solver, never()).terminateEarly();
    }

    @Test
    public void clear_should_stop_solver_and_publish_initial_solution() throws ExecutionException, InterruptedException {
        // set up a situation where solver is running with 1 depot and 2 visits
        VehicleRoutingSolution solution = createSolution(location1, location2, location3);
        when(bestSolutionChangedEvent.isEveryProblemFactChangeProcessed()).thenReturn(true);
        when(bestSolutionChangedEvent.getNewBestSolution()).thenReturn(solution);
        routeOptimizer.addLocation(location1, distanceMatrix);
        routeOptimizer.addLocation(location2, distanceMatrix);
        routeOptimizer.addLocation(location3, distanceMatrix);
        routeOptimizer.bestSolutionChanged(bestSolutionChangedEvent);
        clearInvocations(eventPublisher);

        routeOptimizer.clear();

        assertThat(solver.isSolving()).isFalse();
        verify(solver).terminateEarly();
        verify(solverFuture).get();

        verify(eventPublisher).publishEvent(routeChangedEventArgumentCaptor.capture());
        RouteChangedEvent event = routeChangedEventArgumentCaptor.getValue();
        assertThat(event.depot()).isEmpty();
        assertThat(event.routes()).isEmpty();
    }

    @Test
    public void clear_should_not_fail_when_solver_is_not_solving() {
        routeOptimizer.clear();
    }

    /**
     * Create a solution with 1 vehicle with depot being the first location and visiting all customers specified by
     * the rest of locations.
     * @param domainLocations depot and customer locations
     * @return initialized solution
     */
    private static VehicleRoutingSolution createSolution(Location... domainLocations) {
        VehicleRoutingSolution solution = SolutionUtil.emptySolution();
        Depot depot = SolutionUtil.addDepot(solution, planningLocation(domainLocations[0]));
        SolutionUtil.addVehicle(solution, 1);
        SolutionUtil.moveAllVehiclesTo(solution, depot);

        // create customers
        for (int i = 1; i < domainLocations.length; i++) {
            SolutionUtil.addCustomer(solution, planningLocation(domainLocations[i]));
        }

        // visit all customers
        Standstill previousStandstill = solution.getVehicleList().get(0);
        for (Customer customer : solution.getCustomerList()) {
            customer.setPreviousStandstill(previousStandstill);
            previousStandstill.setNextCustomer(customer);
            previousStandstill = customer;
        }
        return solution;
    }
}
