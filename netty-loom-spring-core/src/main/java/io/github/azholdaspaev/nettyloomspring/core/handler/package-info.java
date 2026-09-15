/**
 * The HTTP channel handlers, which run on the event loop, and the seam that leaves it:
 * {@link HttpRequestHandler} hands each decoded request to a virtual thread, where an
 * {@link HttpRequestDispatcher} answers it through an {@link HttpResponseWriter}. Those two types are
 * what an integrator implements and calls, and what keeps this module free of Spring; every other
 * handler here is a step the starter assembles into the pipeline.
 */
package io.github.azholdaspaev.nettyloomspring.core.handler;
